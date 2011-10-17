;; Copyright (c) 2009-2010 Tunde Ashafa
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
;; IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT
;; INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
;; NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE
;; DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
;; THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
;; (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
;; THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.


(ns ^{:author "Tunde Ashafa"}
  com.ashafa.clutch
  (:require [com.ashafa.clutch.utils :as utils]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.contrib.http.agent :as h])
  (:use com.ashafa.clutch.http-client
        (clojure.contrib core def))
  (:import (java.io File FileInputStream BufferedInputStream)
           (java.net URL)))

(def ^{:private true} highest-supported-charcode 0xfff0)

(def ^{:doc "A very 'high' unicode character that can be used
              as a wildcard suffix when querying views."}
  ; \ufff0 appears to be the highest character that couchdb can support
  ; discovered experimentally with v0.10 and v0.11 ~March 2010
  ; now officially documented at http://wiki.apache.org/couchdb/View_collation
  wildcard-collation-string (str (char highest-supported-charcode)))

(def ^{:private true} watched-databases (ref {}))

(defmacro defdbop
  "Same as defn, but attaches :dbop metadata to the defined var and provides
   implicit coercion of the first `db` argument to a URL instance."
  [name & body]
  `(do
     (defn ~(with-meta name (assoc (meta name)
                              :dbop true))
       ~@body)
     (alter-var-root (var ~name)
       (fn [fn#]
         (fn [db# & args#]
           (apply fn# (utils/url db#) args#))))))

(defdbop couchdb-info
  "Returns information about a CouchDB instance."
  [db]
  (couchdb-request :get (utils/server-url db)))

(defdbop all-databases
  "Returns a list of all databases on the CouchDB server."
  [db]
  (couchdb-request :get (-> db utils/server-url (assoc :path "_all_dbs"))))

(defdbop create-database
  [db]
  (couchdb-request :put db)
  db)

(defdbop database-info
  [db]
  (let [watched-databases @watched-databases]
    (merge (when-let [watchers (watched-databases (str db))]
             {:watchers (keys watchers)})
           (couchdb-request :get db))))

(defdbop get-database
  "Returns a database meta information if it already exists else creates a new database and returns
   the meta information for the new database."
  [db]
  (merge db 
         (or (database-info db)
             (and (create-database db)
                  (database-info db)))))

(defdbop delete-database
  [db]
  (couchdb-request :delete db))

(defdbop replicate-database
  "Takes two arguments (a source and target for replication) which could be a
   string (name of a database in the default Clutch configuration) or a map that
   contains a least the database name (':name' keyword, map is merged with
   default Clutch configuration) and reproduces all the active documents in the
   source database on the target databse."
  [srcdb tgtdb]
  (couchdb-request :post
    (assoc (utils/server-url tgtdb) :path "_replicate")
    :data {:source (str srcdb)
           :target (str tgtdb)}))

(defn- watch-changes-handler
  [url-str watch-key uid agnt]
  (if (h/success? agnt)
    (loop [lines (utils/read-lines (h/stream agnt))]
      (if-let [watched-db (@watched-databases url-str)]
        (when (and (watched-db watch-key) (= uid (:uid (watched-db watch-key))) (not (empty? lines)))
          (future
            (let [line (first lines)]
              (try
               (if (> (count line) 1)
                 ((:callback (watched-db watch-key)) (json/read-json line true)))
               (catch Exception e
                 (dosync
                  (if-let [watched-db (@watched-databases url-str)]
                    (if (watched-db watch-key)
                      (alter watched-databases assoc-in [url-str watch-key :last-error]
                             {:exception e :time (java.util.Date.) :data line}))))))))
          (recur (rest lines)))))))

(defdbop watch-changes
  "Provided a database (database meta <map>, url <string>, or database name <string>) and a callback, watches
   for changes to the database and executes the given callback (takes one argument) on every change
   to a document in the given database, using the meta of the changed document as the only
   argument of the callback."
  [db watch-key callback & {:as options}]
  (let [url-str (str db)
        last-update (:update_seq (database-info db))
        options (merge {:heartbeat 30000 :feed "continuous"} options)
        options (if (:since options)
                  options
                  (assoc options :since last-update))]
    (when last-update
      (dosync
       (let [uid     (str (java.util.UUID/randomUUID))
             watcher {:uid        uid
                      :http-agent (h/http-agent 
                                   (str url-str "/_changes" (utils/map-to-query-str options (constantly false)))
                                   :method "GET"
                                   :handler (partial watch-changes-handler url-str watch-key uid))
                      :callback   callback}]
         (if (@watched-databases url-str)
           (alter watched-databases assoc-in [url-str watch-key] watcher)
           (alter watched-databases assoc url-str {watch-key watcher}))))
      db)))

(defdbop changes-error
  "If the provided database is being watched for changes (see: 'watch-changes'), returns a map
   containing the last exception, the time (java.util.Date) of the exception, and the argument
   supplied to the callback, if an exception occured during execution of the callback."
 [db watch-key]
 (let [watched-database (@watched-databases (str db))]
   (if watched-database
     (:last-error  (watched-database watch-key)))))

(defdbop stop-changes
  "If the provided database changes are being watched (see: 'watch-changes'), stops the execution
   of the callback on every change to the watched database."
  [db & [watch-key]]
  (dosync
    (let [url-key (str db)]
      (if watch-key
        (alter watched-databases #(let [m (update-in % [url-key] dissoc watch-key)]
                                    (if (seq (m url-key))
                                      m
                                      (dissoc m url-key))))
        (alter watched-databases dissoc url-key))))
  db)

(defn- generate-attachment-map
  [files]
  (reduce
   (fn [map ^File file]
     (assoc map
       (keyword (.getName file))
       {:content_type (utils/get-mime-type file)
        :data (-> file FileInputStream. BufferedInputStream.
                utils/convert-input-to-bytes
                utils/encode-bytes-to-base64)
        :length (.length file)}))
   {} files))

(defdbop put-document
  [db document & {:keys [id attachments]}]
  (let [document (merge document
                        (when id {:_id id})
                        (when (seq attachments)
                          (let [files (map #(if (string? %) (File. ^String %) %) attachments)]
                            (if (every? #(and (instance? File %) (.exists ^File %)) files)
                              {:_attachments (generate-attachment-map files)}
                              (throw (IllegalArgumentException.
                                       (str ":attachments provided to put-document must be "
                                            "`java.io.File`s or string paths that name readable "
                                            "files that exist: " attachments)))))))
        result (couchdb-request (if (:_id document) :put :post)
                                (utils/url db (utils/uri-encode (:_id document)))
                                :data document)]
    (and (:ok result)
      (assoc document :_rev (:rev result) :_id (:id result)))))

(defn dissoc-meta
  "dissoc'es the :_id and :_rev slots from the provided map."
  [doc]
  (dissoc doc :_id :_rev))

(defdbop get-document
  "Returns the document identified by the given id. Optional CouchDB document API query parameters
   (rev, attachments, may be provided as keyword arguments."
  [db id & {:as get-params}]
  (when (seq id)
    (couchdb-request :get
      (-> db
        (utils/url (utils/uri-encode id))
        (assoc :query (utils/map-to-query-str
                        get-params
                        utils/encode-compound-values))))))

(defn- document-url
  [database-url document]
  (when-not (:_id document)
    (throw (IllegalArgumentException. "A valid document with an :_id slot is required.")))
  (let [with-id (utils/url database-url (utils/uri-encode (:_id document)))]
    (if-let [rev (:_rev document)]
      (assoc with-id :query (str "rev=" rev))
      with-id)))

(defdbop delete-document
  "Takes a document and deletes it from the database."
  [db document]
  (couchdb-request :delete (document-url db document)))

(defdbop copy-document
  "Copies the provided document (or the document with the given string id)
   to the given new id.  If the destination id identifies an existing
   document, then a document map (with up-to-date :_id and :_rev slots)
   must be provided as a destination argument to avoid a 409 Conflict."
  [db src dest]
  (let [dest (if (map? dest) dest {:_id dest})
        ; TODO yuck; surely we need an id?rev=123 string elsewhere, so this can be rolled into a URL helper fn?
        dest (apply str (:_id dest) (when (:_rev dest) ["?rev=" (:_rev dest)]))]
    (couchdb-request :copy
      (document-url db (if (map? src)
                         src
                         {:_id src}))
      :headers {"Destination" dest})))

;; TODO update-document doesn't provide a lot, now that put-document is here and can update or create as necessary
(let [put-document put-document]
  (defdbop update-document
    "Takes document and a map and merges it with the original. When a function
     and a vector of keys are supplied as the second and third argument, the
     value of the keys supplied are updated with the result of the function of
     their values (see: #'clojure.core/update-in)."
    [db document & [mod & args]]
    (let [document (cond
                     (map? mod) (merge document mod)
                     (fn? mod) (apply mod document args)
                     (nil? mod) document
                     :else (throw (IllegalArgumentException.
                                    "A map or function is needed to update a document.")))]
      (put-document db document))))

(defdbop configure-view-server
  "Sets the query server exec string for views written in the specified :language
   (\"clojure\" by default).  This is intended to be
   a REPL convenience function; see the Clutch README for more info about setting
   up CouchDB to be Clutch-view-server-ready in general terms."
  [db exec-string & {:keys [language] :or {language "clojure"}}]
  (couchdb-request :put
    (assoc db :path (str "_config/query_servers/" (utils/str* language)))
    :data (pr-str exec-string)))

(defn- map-leaves
  [f m]
  (into {} (for [[k v] m]
             (if (map? v)
               [k (map-leaves f v)]
               [k (f v)]))))

(defmulti view-transformer identity)
(defmethod view-transformer :clojure
  [_]
  {:language :clojure
   :compiler (fn [options] pr-str)})
(defmethod view-transformer :default
  [language]
  {:language language
   :compiler (fn [options] str)})

(defmacro view-server-fns
  [options fns]
  (let [[language options] (if (map? options)
                             [(or (:language options) :javascript) (dissoc options :language)]
                             [options])]
    [(:language (view-transformer language))
     `(#'map-leaves ((:compiler (view-transformer ~language)) ~options) '~fns)]))

(let [get-document get-document
      put-document put-document]
  (defn save-design-document
    "Create/update a design document containing functions used for database
     queries/filtering/validation/etc."
    [fn-type db design-document-name [language view-server-fns]]
    (let [design-doc-id (str "_design/" design-document-name)]
      (if-let [design-doc (get-document db design-doc-id)]
        (update-document db design-doc update-in [fn-type] merge view-server-fns)
        (put-document db {fn-type view-server-fns
                          :language language
                          :_id design-doc-id})))))

(def ^{:dbop true} save-view
  "Create or update a design document containing views used for database queries."
  (partial save-design-document :views))

(def ^{:dbop true} save-filter
  "Create a filter for use with CouchDB change notifications API via 'watch-changes'."
  (partial save-design-document :filters))

(defn- get-view*
  "Get documents associated with a design document. Also takes an optional map
   for querying options, and a second map of {:key [keys]} to be POSTed.
   (see: http://wiki.apache.org/couchdb/HTTP_view_API)."
  [db path & [query-params-map post-data-map]]
  (couchdb-request (if (empty? post-data-map) :get :post)
    (assoc (utils/url db path)
      :query (utils/map-to-query-str query-params-map (apply utils/forgiving-keyset '[key startkey endkey])))
    :data (when (seq post-data-map) post-data-map)))

(defdbop get-view
  "Get documents associated with a design document. Also takes an optional map
   for querying options, and a second map of {:key [keys]} to be POSTed.
   (see: http://wiki.apache.org/couchdb/HTTP_view_API)."
  [db design-document view-key & [query-params-map post-data-map :as args]]
  (apply get-view* db
         (:path (utils/url "_design" design-document "_view" (utils/str* view-key)))
         args))

(defdbop all-documents
  "Returns the meta (_id and _rev) of all documents in a database. By adding
   the key ':include_docs' with a value of true to the optional query params map
   you can also get the full documents, not just their meta. Also takes an optional
   second map of {:keys [keys]} to be POSTed.
   (see: http://wiki.apache.org/couchdb/HTTP_view_API)."
  [db & [query-params-map post-data-map :as args]]
  (apply get-view* db "_all_docs" args))

(defdbop ad-hoc-view
  "One-off queries (i.e. views you don't want to save in the CouchDB database). Ad-hoc
   views should be used only during development. Also takes an optional map for querying
   options (see: http://wiki.apache.org/couchdb/HTTP_view_API)."
  [db [language view-server-fns] & [query-params-map]]
  (get-view* db "_temp_view"  query-params-map (into {:language language} view-server-fns)))

(defdbop bulk-update
  "Takes a sequential collection of documents (maps) and inserts or updates (if \"_id\" and \"_rev\" keys
   are supplied in a document) them with in a single request.

   Optional keyword args may be provided, and are sent along with the documents
   (e.g. for \"all-or-nothing\" semantics, etc)."
  [db documents & {:as options}]
  (couchdb-request :post
    (utils/url db "_bulk_docs")
    :data (assoc options :docs documents)))

(defdbop update-attachment
  "Takes a document, file (either a string path to the file, a java.io.File object, or an InputStream)
   and optionally, a new file name in lieu of the file name of the file argument and a mime type
   then inserts (or updates if the file name of the attachment already exists in the document)
   the file as an attachment to the document."
  [db document attachment & [file-key mime-type]]
  (let [^File file (cond (string? attachment) (java.io.File. ^String attachment)
                         (instance? java.io.File attachment) attachment)
        stream (cond
                 file (-> file java.io.FileInputStream. java.io.BufferedInputStream.)
                 (instance? java.io.InputStream attachment) attachment
                 :else (throw (IllegalArgumentException.
                                "Path string, java.io.File, or InputStream object is expected.")))
        file-name (or file-key (and file (.getName file))
                    (throw (IllegalArgumentException. "Must provide a file-key if using InputStream as attachment data.")))]
    (couchdb-request :put
      (-> db
        (document-url document)
        (utils/url (if (keyword? file-name)
                     (name file-name)
                     file-name)))
      :data stream
      :data-type (or mime-type (and file (utils/get-mime-type file))))))

(defdbop delete-attachment
  "Deletes an attachemnt from a document."
  [db document file-name]
  (couchdb-request :delete (utils/url (document-url db document) file-name)))

(defdbop get-attachment
  "Returns an InputStream reading the named attachment to the specified/provided document
   or nil if the document or attachment does not exist.

   Hint: use the copy or to-byte-array fns in c.c.io to easily redirect the result."
  [db doc-or-id attachment-name]
  (let [doc (if (map? doc-or-id) doc-or-id (get-document doc-or-id))
        attachment-name (if (keyword? attachment-name)
                          (name attachment-name)
                          attachment-name)]
    (when (-?> doc :_attachments (get (keyword attachment-name)))
      (couchdb-request :get
                       (-> (document-url db doc)
                         (utils/url attachment-name)
                         (assoc :read-json-response false))))))

(def ^{:private true} database-operations
  (->> (ns-publics *ns*)
    vals
    (filter (comp :dbop meta))
    (map #(.setDynamic %))
    doall))

(defmacro with-db
  "Takes a URL, database name (useful for localhost only), or an instance of
   com.ashafa.clutch.utils.URL.  That value is used to configure the subject
   of all of the operations within the dynamic scope of body of code."
  [database & body]
  `(with-bindings (into {} (for [var @#'com.ashafa.clutch/database-operations]
                             [var (partial @var (utils/url ~database))]))
     ~@body))
