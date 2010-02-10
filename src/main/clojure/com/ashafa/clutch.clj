;; Copyright (c) 2009 Tunde Ashafa
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


(ns com.ashafa.clutch
  (:import (java.io File InputStream FileInputStream BufferedInputStream))
  (:require [com.ashafa.clutch.utils :as utils])
  (:use com.ashafa.clutch.http-client))


(declare config)

;;; default Clutch configuration...
(def *defaults* (ref {:host "localhost"
                      :port 5984
                      :language "javascript"}))

; the standard "replacement character" seems like as reasonable a choice as any
(def #^{:doc "A very 'high' unicode character that can be used
              as a wildcard suffix when querying views."}
  *wildcard-collation-str* "\ufffd")

(defn set-clutch-defaults!
  "Sets Clutch default configuration:
        {:host     <ip (defaults to \"localhost\")>
         :port     <port (defaults to 5984)>
         :language <language the view server uses (see: README)>
         :username <username (if http authentication is enabled)>
         :password <password (if http authentication is enabled)>}"
  [configuration-map]
  (dosync (alter *defaults* merge configuration-map)))

(defn database-arg-type [arg]
    (cond (string? arg) :name
          (and (map? arg) (contains? arg :name)) :meta
          (instance? java.net.URL arg) :url
          :else (throw
                 (IllegalArgumentException. 
                  "Either a string or a map with a ':name' key is required."))))

(defn url->db-meta
  "Given a java.net.URL, returns a map with slots aligned with *defaults*.
   Supports usage of URLs with with-db, etc."
  [#^java.net.URL url]
  {:host (.getHost url)
   :port (.getPort url)
   :name (.getPath url)})

(defmacro #^{:private true} check-and-use-document
  [doc & body]
  `(if-let [id# (~doc :_id)]
     (binding [config 
               (assoc config :name 
                   (str (config :name) "/" id# "?rev=" (:_rev ~doc)))]
       (do ~@body))
     (throw 
      (IllegalArgumentException. "A valid document is required."))))

(defmacro with-clj-view-server
  "Takes one or two functions and returns a map-reduce map (with the functions
   serialized as strings) used by the Clojure view server."
  ([map-form]
     `(with-clj-view-server ~(pr-str map-form) nil))
  ([map-form reduce-form]
     (let [map-reduce-map {:map (if (string? map-form) map-form (pr-str map-form))}]
       (if reduce-form
          (assoc map-reduce-map :reduce (pr-str reduce-form))
          map-reduce-map))))

(defmacro with-db
  "Takes a string (database name) or map (database meta) with a body of forms. It 
   then binds the database information to the Clutch configuration and then executes
   the body."
  [database & body]
  `(let [arg-type# (database-arg-type ~database)]
    (binding [config (merge @*defaults* 
                             (cond (= :name arg-type#) {:name ~database}
                                   (= :meta arg-type#) ~database
                                   (= :url arg-type#) (url->db-meta ~database)))]
       (do ~@body))))

(defn couchdb-info
  "Returns the CouchDB information."
  ([]
     (couchdb-info nil))
  ([database]
     (couchdb-request (dissoc (merge @*defaults* database) :name) :get)))

(defn all-couchdb-databases
  "Returns a list of all databases on the CouchDB server."
  ([]
     (all-couchdb-databases nil))
  ([database-meta]
     (couchdb-request (dissoc (merge @*defaults* database-meta) :name) :get "_all_dbs")))

(defmulti create-database
  "Takes a map (cofiguration of a CouchDB server with a :name key) or string (using the
   string and the :name key and merging it into the default Clutch configuration) and
   creates a database."
  database-arg-type)

(defmethod create-database :name
  [database-name]
  (create-database (assoc @*defaults* :name database-name)))

(defmethod create-database :meta
  [database-meta]
  (merge database-meta
         (couchdb-request
          (dissoc (merge @*defaults* database-meta) :name)
          :put (:name database-meta))))

(defmethod create-database :url
  [url]
  (-> url url->db-meta create-database))

(defmulti delete-database
  "Takes a database name and deletes the corresponding database."
  database-arg-type)

(defmethod delete-database :name
  [database-name]
  (delete-database (assoc @*defaults* :name database-name)))

(defmethod delete-database :meta
  [database-meta]
  (couchdb-request
   (dissoc (merge @*defaults* database-meta) :name) 
   :delete (:name database-meta)))

(defmethod delete-database :url
  [url]
  (-> url url->db-meta delete-database))

(defmulti database-info
  "Takes a database name and returns the meta information about the database."
  database-arg-type)

(defmethod database-info :name
  [database-name]
  (database-info (assoc @*defaults* :name database-name)))

(defmethod database-info :meta
  [database-meta]
  (couchdb-request
   (dissoc (merge @*defaults* database-meta) :name) 
   :get (:name database-meta)))

(defmethod database-info :url
  [url]
  (-> url url->db-meta database-info))

(defn replicate-database
  "Takes two arguments (a source and target for replication) which could be a
   string (name of a database in the default Clutch configuration) or a map that 
   contains a least the database name (':name' keyword, map is merged with
   default Clutch configuration) and reproduces all the active documents in the
   source database on the target databse."
  [source-database target-database]
  (let [get-meta    (fn [db]
                      (let [arg-type (database-arg-type db)]
                        (merge @*defaults*
                               (cond (= :name arg-type) {:name db}
                                     (= :meta arg-type) db
                                     (= :url arg-type) (url->db-meta db)))))
        source-meta (get-meta source-database)
        target-meta (get-meta target-database)]
    (couchdb-request (dissoc target-meta :name) :post "_replicate"
                     {:source (utils/get-database-url source-meta)
                      :target (utils/get-database-url target-meta)})))

(defmulti create-document
  "Takes a map and creates a document with an auto generated id, returns the id
   and revision in a map."
  (fn [& args]
    (cond (not (map? (first args)))
          (throw (IllegalArgumentException. "Document must be a map."))
          (vector? (nth args 1 nil)) :with-attachments-and-generate-id
          (vector? (nth args 2 nil)) :with-attachments-and-supplied-id
          :else :default)))

(defn- all-files?
  [files]
  (or (every? #(= java.io.File (class %)) files)
      (throw  (IllegalArgumentException. "File expected."))))

(defn- generate-attachment-map
  [files]
  (reduce
   #(assoc %1 (.getName %2)
           {:content_type (utils/get-mime-type %2)
            :data         (-> %2 FileInputStream. BufferedInputStream.
                            utils/convert-input-to-bytes
                            utils/encode-bytes-to-base64)})
   {} files))

(defmethod create-document :with-attachments-and-generate-id
  [document-map files]
  (if (all-files? files)
    (create-document
     (assoc document-map :_attachments (generate-attachment-map files)))))

(defmethod create-document :with-attachments-and-supplied-id
  [document-map id files]
  (if (all-files? files)
    (create-document
     (assoc document-map :_attachments (generate-attachment-map files)) id)))

(defmethod create-document :default
  ([document-map]
     (create-document document-map nil))
  ([document-map id]
     (couchdb-request config (if (nil? id) :post :put) id document-map)))

(defn get-document
  "Takes an id and an optional query parameters map as arguments and returns a
   document (as a map) with an id that matches the id argument."
  ([id]
     (get-document id {}))
  ([id query-params-map]
     (if (and id (not (empty? id)))
       (couchdb-request config :get (str id (utils/map-to-query-str query-params-map))))))

(defn delete-document
  "Takes a document and deletes it from the database."
  [document]
  (check-and-use-document document
    (couchdb-request config :delete)))

(defmulti update-document
  "Takes document and a map and merges it with the original. When a function
   and a vector of keys are supplied as the second and third argument, the 
   value of the keys supplied are upadated with the result of the function of
   their values (see: #'clojure.core/update-in)."
  (fn [& args] 
    (let [targ (second args)]
      (cond (fn? targ)  :fn
            (map? targ) :map
            :else (throw (IllegalArgumentException. 
                          "A map or function is needed to update a document."))))))

(defmethod update-document :fn 
  [document update-fn update-keys]
  (check-and-use-document document
    (couchdb-request config :put nil
                       (update-in document update-keys update-fn))))

(defmethod update-document :map 
  [document merge-map]
  (check-and-use-document document
    (couchdb-request config :put nil (merge document merge-map))))

(defn get-all-documents
  "Returns the meta (_id and _rev) of all documents in a database. By adding 
   {:include_docs true} to the map for optional querying options argument
   you can also get the documents data, not just their meta. Also takes an optional
    map for querying options (see: http://bit.ly/gxObh)."
  ([]
     (get-all-documents {}))
  ([query-params-map]
     (couchdb-request config :get
                      (str "_all_docs" (utils/map-to-query-str query-params-map)))))

(defn create-view
  "Create a design document used for database queries."
  [design-document-name view-key view-server-map]
     (let [design-doc-id (str "_design/" design-document-name)]
       (if-let [design-doc (get-document design-doc-id)]
         (update-document design-doc #(assoc % view-key view-server-map) [:views])
         (create-document {:language (config :language)
                           :views (hash-map view-key view-server-map)} design-doc-id))))

(defn get-view
  "Get documents associated with a design document. Also takes an optional map
   for querying options (see: http://bit.ly/gxObh)."
  ([design-document view-key]
     (get-view design-document view-key {}))
  ([design-document view-key query-params-map]
     (couchdb-request config :get 
                      (str "_design/" design-document "/_view/" (name view-key)
                           (utils/map-to-query-str query-params-map)))))

(defn ad-hoc-view
  "One-off queries (i.e. views you don't want to save in the CouchDB database). Ad-hoc
   views are only good during development. Also takes an optional map for querying
   options (see: http://bit.ly/gxObh)."
  ([map-reduce-fns-map]
     (ad-hoc-view map-reduce-fns-map {}))
  ([map-reduce-fns-map query-params-map]
     (couchdb-request config :post 
                      (str "_temp_view" (utils/map-to-query-str query-params-map))
                      (if-not (contains? map-reduce-fns-map :language)
                        (assoc map-reduce-fns-map :language (config :language))
                        map-reduce-fns-map))))

(defn bulk-update
  "Takes a vector of documents (maps) and inserts or updates (if \"_id\" and \"_rev\" keys
   are supplied in a document) them with a single CouchDB request."
  ([documents-vector]
     (bulk-update documents-vector nil nil))
  ([documents-vector update-map]
     (bulk-update documents-vector update-map nil))
  ([documents-vector update-map options-map]
     (couchdb-request config :post "_bulk_docs"
                      (merge {:docs (if update-map 
                                      (map #(merge % update-map) documents-vector) 
                                      documents-vector)}
                             options-map))))

(defn update-attachment
  "Takes a document, file (either a string path to the file or a java.io.File object)
   and optionally, a new file name in lieu of the file name of the file argument and a mime type,
   then inserts (or updates if the file name of the attachment already exists in the document)
   the file as an attachment to the document."
  [document attachment & [file-key mime-type]]
  (let [file (cond (string? attachment) (File. attachment)
               (instance? File attachment) attachment)
        stream (cond
                 file (-> file FileInputStream. BufferedInputStream.)
                 (instance? InputStream attachment) attachment
                 :else (throw (IllegalArgumentException.
                                "Path string, java.io.File, or InputStream object is expected.")))
        file-name (or file-key (and file (.getName file))
                    (throw (IllegalArgumentException. "Must provide a file-key if using InputStream as attachment data.")))]
    (check-and-use-document document
      (couchdb-request config :put
        (if (keyword? file-name)
          (name file-name) file-name)
        stream
        (or mime-type (and file (utils/get-mime-type file)))))))

(defn delete-attachment
  "Deletes an attachemnt from a document."
  [document file-name]
  (check-and-use-document document
    (couchdb-request config :delete file-name)))