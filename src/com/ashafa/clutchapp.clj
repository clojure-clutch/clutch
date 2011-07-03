;; Copyright (c) 2009 Chas Emerick
;; All rights reserved.

;; Redistribution and use in source and binary forms, with or without
;; modification, are permitted provided that the following conditions
;; are met:
;; 1. Redistributions of source code must retain the above copyright
;;    notice, this list of conditions and the following disclaimer.
;; 2. Redistributions in binary form must reproduce the above copyright
;;    notice, this list of conditions and the following disclaimer in the
;;    documentation and/or other materials provided with the distribution.
;; 3. The name of the author may not be used to endorse or promote products
;;    derived from this software without specific prior written permission.

;; THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
;; IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
;; OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
;; IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
;; INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
;; NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
;; DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
;; THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
;; (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
;; THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

(ns
 #^{:author "Chas Emerick"
    :doc "This is a spike of a couchapp-compatible design document sync tool
          built on clutch. The objectives here are much less ambitious and
          not necessarily the same as couchapp's.

          Specifically, these utilities only support pushing and cloning
          design documents as a way of initializing databases and views as
          required by applications using a couchdb instance as a data source.
          i.e. enabling clojure webapps to carry view definitions and
          prep a couch for use itself automagically rather than having a separate
          db configuration/preparation stage prior to app deployment.

          Some notes:

          * only _id, language, and views slots are copied in either direction
          * clone-all and push-all are available for operating over all design
            documents in a database/directory; the former will create the target
            database if necessary."}
  com.ashafa.clutchapp
  (:require [clojure.java.io :as jio]
            [clojure.tools.logging :as log])
  (:use com.ashafa.clutch)
  (:import java.io.File java.net.URL))

(def +language->ext+ {"javascript" "js"
                      "clojure" "clj"
                      "python" "py"
                      "ruby" "rb"})

(declare ^:dynamic *lang-ext*)

(def #^{:doc "Bindable var to control whether design documents are overwritten
              when pushing. If false (it's true by default), and a design document
              already exists, the push fns will become no-ops (logging that fact)."}
  *push-overwrite?* true)

(defn- load-files
  "Loads the files within the directory named by the paths,
   returning a seq of [:filename content] vectors.  Note that file
   extensions are trimmed in the process of creating :filename."
  [dir paths]
  (for [^String path paths
        :let [f (jio/file dir path)
              dot (.lastIndexOf path ".")]
        :when (.exists f)]
    [(-> path (subs 0 (if (neg? dot) (count path) dot)) keyword)
     (slurp f)]))

(defn- load-view
  "Loads a view from a view directory, returning a map of
   {:view-name {:map \"<some js>\" :reduce \"<some js>\"}},
   with map and reduce content coming from map.js and reduce.js
   files in the view dir.

   @todo add support for other languages based on the design doc's
   :language slot, e.g. map.clj, reduce.clj"
  [^File view-dir]
  {(-> view-dir .getName keyword)
   (into {} (load-files view-dir (map #(str % \. *lang-ext*) ["map" "reduce"])))})

(defn- load-views
  "Loads all views under the given design document root directory path."
  [design-doc-path]
  (let [views-root (jio/file design-doc-path "views")
        view-dirs (filter #(.isDirectory ^File %) (.listFiles views-root))]
    (->> view-dirs
      (map load-view)
      (reduce merge))))

(defn- load-design-doc
  "Loads the design document rooted at the given directory. The value of the :_id
   slot in the returned map will be the contents of the _id file in the directory,
   *not* _design/<directory name>. Throws an IllegalStateException if either _id or
   language files are not found in that directory."
  [design-doc-path]
  (let [base-slots (load-files design-doc-path ["_id" "language"])
        base-doc (into {} base-slots)]
    (when-not (== 2 (count base-slots))
      (throw (IllegalStateException.
               (str "_id and/or languages files not found in design doc directory " design-doc-path))))
    (binding [*lang-ext* (-> base-doc :language +language->ext+)]
      (assoc base-doc
        :views (load-views design-doc-path)))))

(defn load-all-ddocs
  "Utility fn used by push-all for loading a set of design documents rooted
   in a provided directory (or, returns a sequential arg untouched).

   This is also helpful in a webapp build process for bundling up the design
   documents associated with the webapp into a single json file that can be
   used to ramp up the app's couchdb instance at webapp init time.  e.g.:

   Build-time: (-> \"some file path\" load-all-ddocs pr),
         and push the result into your war file
   Runtime: (-> \"war rsrc path\" slurp read (push-all \"http://dbhost:5984/dbname\"))
   "
  [dir-or-ddocs]
  (cond
    (or (string? dir-or-ddocs)
      (instance? File dir-or-ddocs)) (->> dir-or-ddocs jio/file .listFiles
                                       (filter #(.isDirectory ^File %))
                                       (map load-design-doc))
    (sequential? dir-or-ddocs) dir-or-ddocs
    :else (throw (IllegalArgumentException.
                   (str "Don't know how to make a seq of design documents from arg of type "
                     (class dir-or-ddocs))))))

(defn push
  "Pushes a single design document, to the database specified by the URL.
   The design document argument can be a string or a map: if the former,
   the design document will be loaded from the directory path specified by
   that string; if the latter, the map will be used directly as the
   design document.

   The database must exist, or an IllegalStateException will be thrown."
  [design-doc db-url]
  (let [doc (if (string? design-doc)
              (load-design-doc design-doc)
              design-doc)
        url (jio/as-url db-url)]
    (with-db url
      (when-not (database-info url)
        (throw (IllegalStateException. (str "Database at " db-url " does not exist."))))
      (let [remote-doc (get-document (:_id doc))]
        (if remote-doc
          (if *push-overwrite?*
            (update-document remote-doc doc)
            (log/warn (str "design document already exists @ " db-url \/ (:_id doc)
                        " and overwrites are currently disabled via *push-overwrite?*.")))
          (create-document doc))))))

(defn push-all
  "Pushes all design documents within the provided directory or already-loaded
   seq of design document maps to the database
   specified by the URL.  If the database does not exist, it will be created."
  [dir-or-ddocs db-url]
  (let [design-docs (load-all-ddocs dir-or-ddocs)
        url (jio/as-url db-url)]
    (when-not (database-info url)
      (create-database url))
    (doseq [dddir design-docs]
      (push dddir db-url))))

(defn- write-view
  [views-dir [vname fns]]
  (let [view-dir (jio/file views-dir (name vname))]
    (assert (or (.exists view-dir) (.mkdirs view-dir)))
    (doseq [[k v] fns
            :let [filename (-> k name (str \. *lang-ext*))]]
      (-> view-dir (jio/file filename) (spit v)))))

(defn clone
  "Clones the design document at the provided URL to the specified
   destination directory path.  This operation clobbers any existing
   content in the destination directory."
  [design-doc-url dest-path]
  (let [[_ db-path ddoc-id] (re-find #"[^/](/[^/]+)/([^/]+/[^/]+)" design-doc-url)
        db-url (-> design-doc-url jio/as-url (URL. db-path))
        ddoc (with-db db-url (get-document ddoc-id))
        views-dir (jio/file dest-path "views")
        root-dir (jio/file dest-path)]
    (binding [*lang-ext* (-> ddoc :language +language->ext+)]
      (assert (or (.exists root-dir) (.mkdirs root-dir)))
      (-> dest-path (jio/file "_id") (spit (:_id ddoc)))
      (-> dest-path (jio/file "language") (spit (:language ddoc)))
      (assert (or (.exists views-dir) (.mkdirs views-dir)))
      (doseq [views (:views ddoc)]
        (write-view (jio/file dest-path "views") views)))))

(defn clone-all
  "Clones all of the design documents in the database rooted at the
   provided URL to a set of directories in the specified destination
   directory path.  This operation clobbers any existing
   content in the destination directory."
  [db-url dest-path]
  (with-db (jio/as-url db-url)
    (doseq [^String ddoc-id (->> (get-all-documents-meta
                                  {:startkey "_design/"
                                   :endkey (str "_design/" *wildcard-collation-string*)})
                                 :rows (map :id))
            :let [[_ ddoc-name] (.split ddoc-id "/")]]
      (clone
        (-> db-url (str "/") URL. (URL. ddoc-id) .toExternalForm)
        (jio/file dest-path ddoc-name)))))

(defn clone-all-dbs
  "Clones all design documents from all databases at the couchdb instance
   specified by the URL to the destination path provided. This operation
   clobbers any existing content in the destination directory."
  [couch-url dest-path]
  (doseq [db (-> couch-url jio/as-url url->db-meta all-databases)
          :let [db-dest-dir (jio/file dest-path db)]]
    (assert (or (.exists db-dest-dir) (.mkdirs db-dest-dir)))
    (clone-all (-> couch-url jio/as-url (URL. db)) db-dest-dir)))
