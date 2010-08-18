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
;; IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
;; INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
;; NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
;; DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
;; THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
;; (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
;; THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.


(ns #^{:author "Tunde Ashafa"}
  com.ashafa.clutch
  (:require [com.ashafa.clutch.utils :as utils]
            [clojure.contrib.json :as json]
            [clojure.contrib.io :as io]
            [clojure.contrib.http.agent :as h])
  (:use com.ashafa.clutch.http-client
        (clojure.contrib core def)))


(declare config)

;; default clutch configuration
(def *defaults* (ref {:host     "localhost"
                      :port     5984
                      :ssl-port 443
                      :language "clojure"}))

(def #^{:doc "A very 'high' unicode character that can be used
              as a wildcard suffix when querying views."}
  ; \ufff0 appears to be the highest character that couchdb can support
  ; discovered experimentally with v0.10 and v0.11 ~March 2010
  *wildcard-collation-string* "\ufff0")

(defn set-clutch-defaults!
  "Sets Clutch default configuration:
        {:host     <ip (defaults to \"localhost\")>
         :port     <port (defaults to 5984)>
         :language <language the CouchDB view server uses (see: README)>
         :username <username (if http authentication is enabled)>
         :password <password (if http authentication is enabled)>}"
  [configuration-map]
  (dosync (alter *defaults* merge configuration-map)))

(def #^{:private true} watched-databases (ref {}))

(defn database-arg-type
  [& args]
  (let [arg (first args)]
    (cond (string? arg) :string
          (and (map? arg) (contains? arg :name)) :meta
          (instance? java.net.URL arg) :url
          :else (throw
                 (IllegalArgumentException. 
                  "Either a database name, url, or a map with a ':name' key is required.")))))

(defn url->db-meta
  "Given a java.net.URL, returns a map with slots aligned with *defaults*.
   Supports usage of URLs with with-db, etc."
  [#^java.net.URL url]
  {:host (.getHost url)
   :port (.getPort url)
   :name (.getPath url)})

(defn- doc->rev-query-string
  [doc]
  (apply str
    (utils/uri-encode (:_id doc))
    (when-let [rev (:_rev doc)]
      ["?rev=" rev])))

(defmacro- check-and-use-document
  [doc & body]
  `(if-let [id# (~doc :_id)]
     (binding [config (assoc config :name 
                        (str (config :name) "/" (doc->rev-query-string ~doc)))]
       (do ~@body))
     (throw 
      (IllegalArgumentException. "A valid document is required."))))


(defmacro with-clj-view-server
  "Takes a map and serializes the values of each key as a string for use by the Clojure view server."
  ([view-server-map]
     (reduce #(assoc %1 %2 (pr-str (%2 view-server-map))) {} (keys view-server-map)))) 

(defmacro with-db
  "Takes a string (database name) or map (database meta) with a body of forms. It 
   then binds the database information to the Clutch configuration and then executes
   the body."
  [database & body]
  `(let [arg-type# (database-arg-type ~database)]
    (binding [config (merge @*defaults* 
                            (cond (= :string arg-type#) (if (re-find #"^https?:" ~database) 
                                                          (utils/url->db-meta ~database)
                                                          {:name ~database})
                                  (= :meta arg-type#) ~database
                                  (= :url arg-type#) (url->db-meta ~database)))]
      (do ~@body))))

(defn set-clutch-defaults!
  "Sets Clutch default CouchDB configuration:
        {:host     <ip (defaults to \"localhost\")>
         :port     <port (defaults to 5984)>
         :language <language the CouchDB view server uses (see: README)>
         :username <username (if http authentication is enabled)>
         :password <password (if http authentication is enabled)>}"
  [configuration-map]
  (dosync (alter *defaults* merge configuration-map)))

(defn couchdb-info
  "Returns informataion about a CouchDB instance."
  ([]
     (couchdb-info nil))
  ([db-meta]
     (couchdb-request (dissoc (merge @*defaults* db-meta) :name) :get)))

(defn all-databases
  "Returns a list of all databases on the CouchDB server."
  ([]
     (all-databases nil))
  ([db-meta]
     (couchdb-request (dissoc (merge @*defaults* db-meta) :name) :get :command "_all_dbs")))

(defmulti create-database
  "Takes a map (cofiguration of a CouchDB server with a :name key) or string (using the
   string and the :name key and merging it into the default Clutch configuration) and
   creates a database."
  database-arg-type)

(defmethod create-database :string
  [db-string]
  (create-database (if (re-find #"^https?:" db-string) 
                     (utils/url->db-meta db-string)
                     (assoc @*defaults* :name db-string))))

(defmethod create-database :url
  [^java.net.URL url]
  (create-database (str url)))

(defmethod create-database :meta
  [db-meta]
  (merge @*defaults* db-meta
         (couchdb-request
          (dissoc (merge @*defaults* db-meta) :name)
          :put
          :command (:name db-meta))))

(defmulti database-info
  "Takes a database name and returns the meta information about the database."
  database-arg-type)

(defmethod database-info :string
  [db-string]
  (database-info (if (re-find #"^https?:" db-string) 
                     (utils/url->db-meta db-string)
                     (assoc @*defaults* :name db-string))))

(defmethod database-info :url
  [^java.net.URL url]
  (database-info (str url)))

(defmethod database-info :meta
  [db-meta]
  (let [watched-databases @watched-databases
        url-key           (utils/db-meta->url db-meta)]
    (merge (if-let [watchers (watched-databases url-key)] {:watchers (vec (keys watchers))})
           (couchdb-request
            (dissoc (merge @*defaults* db-meta) :name) 
            :get
            :command (:name db-meta)))))
    
(defmulti get-database
  "Returns a database meta information if it already exists else creates a new database and returns
   the meta information for the new database."
  database-arg-type)

(defmethod get-database :string
  [db-string]
  (let [db-meta (if (re-find #"^https?:" db-string) 
                  (utils/url->db-meta db-string)
                  (assoc @*defaults* :name db-string))]
    (if (database-info db-meta)
      db-meta
      (merge @*defaults* (create-database db-meta)))))

(defmethod get-database :meta
  [db-meta]
  (if (database-info db-meta)
    (merge @*defaults* db-meta)
    (merge @*defaults* (create-database db-meta))))
 
(defmulti delete-database
  "Takes a database name and deletes the corresponding database."
  database-arg-type)

(defmethod delete-database :string
  [db-string]
  (delete-database (if (re-find #"^https?:" db-string) 
                     (utils/url->db-meta db-string)
                     (assoc @*defaults* :name db-string))))

(defmethod delete-database :url
  [^java.net.URL url]
  (delete-database (str url)))

(defmethod delete-database :meta
  [db-meta]
  (couchdb-request
   (dissoc (merge @*defaults* db-meta) :name) 
   :delete
   :command (:name db-meta)))
 
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
                               (cond (= :string arg-type) (if (re-find #"^https?:" db) 
                                                            (utils/url->db-meta db)
                                                            (assoc @*defaults* :name db))
                                     (= :meta arg-type) db))))
        source-meta (get-meta source-database)
        target-meta (get-meta target-database)]
    (couchdb-request (dissoc target-meta :name) :post
      :command "_replicate"
      :data {:source (utils/db-meta->url source-meta)
             :target (utils/db-meta->url target-meta)})))

(defmulti watch-changes
  "Provided a database (database meta <map>, url <string>, or database name <string>) and a callback, watches
   for changes to the database and executes the given callback (takes one argument) on every change
   to a document in the given database, using the meta of the changed document as the only
   argument of the callback."
  database-arg-type)

(defn- watch-changes-handler
  [url-str watch-key uid agnt]
  (if (h/success? agnt)
    (loop [lines (io/read-lines (h/stream agnt))]
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
                             {:execption e :time (java.util.Date.) :data line}))))))))
          (recur (rest lines)))))))

(defmethod watch-changes :string
  [db-string watch-key callback & options]
  (apply watch-changes (if (re-find #"^https?:" db-string) 
                         (utils/url->db-meta db-string)
                         (assoc @*defaults* :name db-string)) watch-key callback options))

(defmethod watch-changes :meta
  [db-meta watch-key callback & options]
  (let [url-str     (utils/db-meta->url db-meta)
        since-seq   (:update_seq (database-info db-meta))
        options-map (merge (first options) {:heartbeat 30000 :feed "continuous" :since since-seq})]
    (when since-seq
      (dosync
       (let [uid     (.getTime (java.util.Date.))
             watcher {:uid        uid
                      :http-agent (h/http-agent 
                                   (str url-str "/_changes" (utils/map-to-query-str options-map false))
                                   :method "GET"
                                   :handler (partial watch-changes-handler url-str watch-key uid))
                      :callback   callback}]
         (if (@watched-databases url-str)
           (alter watched-databases assoc-in [url-str watch-key] watcher)
           (alter watched-databases assoc url-str {watch-key watcher}))))
      db-meta)))

(defmulti changes-error
  "If the provided database is being watched for changes (see: 'watch-changes'), returns a map
   containing the last exception, the time (java.util.Date) of the exception, and the argument
   supplied to the callback, if an exception occured during execution of the callback."
  database-arg-type)

(defmethod changes-error :string
  [db-string watch-key]
  (changes-error (if (re-find #"^https?:" db-string) 
                   (utils/url->db-meta db-string)
                   (assoc @*defaults* :name db-string)) watch-key))

(defmethod changes-error :meta
 [db-meta watch-key]
 (let [url-str          (utils/db-meta->url db-meta)
       watched-database (@watched-databases url-str)]
   (if watched-database
     (:last-error  (watched-database watch-key)))))

(defmulti stop-changes
  "If the provided database changes are being watched (see: 'watch-changes'), stops the execution 
   of the callback on every change to the watched database."
  database-arg-type)

(defmethod stop-changes :string
  ([db-string]
     (stop-changes db-string nil))
  ([db-string watch-key]
     (stop-changes (if (re-find #"^https?:" db-string) 
                     (utils/url->db-meta db-string)
                     (assoc @*defaults* :name db-string)) watch-key)))

(defmethod stop-changes :meta
  ([db-meta]
     (stop-changes db-meta nil))
  ([db-meta watch-key]
     (dosync
      (let [url-key (utils/db-meta->url db-meta)]
        (if watch-key
          (alter watched-databases update-in [url-key watch-key] nil)
          (alter watched-databases dissoc url-key))))
     db-meta))

(defmulti create-document
  "Takes a map and creates a document with an auto generated id, returns the id
   and revision in a map."
  (fn [& args]
    (cond (not (map? (first args)))
          (throw (IllegalArgumentException. "Document must be a map."))
          (vector? (nth args 1 nil)) :with-attachments-and-generate-id
          (vector? (nth args 2 nil)) :with-attachments-and-supplied-id
          :else :default)))

(defn- get-all-files
  [files]
  (let [all-files (map #(if (string? %) (java.io.File. %) %) files)]
    (if (every? #(and (instance? java.io.File %) (.exists %)) all-files)
      all-files
      (throw  (IllegalArgumentException. "File expected or not found.")))))

(defn- generate-attachment-map
  [files]
  (reduce
   #(assoc %1 (keyword (.getName %2))
           {:content_type (utils/get-mime-type %2)
            :data         (-> %2 java.io.FileInputStream. java.io.BufferedInputStream.
                              utils/convert-input-to-bytes
                              utils/encode-bytes-to-base64)
            :length       (.length %2)})
   {} files))

(defmethod create-document :with-attachments-and-generate-id
  [document-map files]
  (if-let [all-files (get-all-files files)]
    (create-document
     (assoc document-map :_attachments (generate-attachment-map all-files)))))

(defmethod create-document :with-attachments-and-supplied-id
  [document-map id files]
  (if-let [all-files (get-all-files files)]
    (create-document
     (assoc document-map :_attachments (generate-attachment-map all-files)) id)))

(defmethod create-document :default
  ([document-map]
     (create-document document-map nil))
  ([document-map id]
     (if-let [new-document-meta (couchdb-request config (if (nil? id) :post :put)
                                  :command (utils/uri-encode id) 
                                  :data document-map)]
       (assoc document-map :_rev (new-document-meta :rev) :_id (new-document-meta :id)))))

(defn dissoc-meta
  "dissoc'es the :_id and :_rev slots from the provided map."
  [doc]
  (dissoc doc :_id :_rev))

(defn get-document
  "Takes an id and an optional query parameters map as arguments and returns a
   document (as a map) with an id that matches the id argument."
  ([id]
     (get-document id {}))
  ([id query-params-map]
     (if (and id (not (empty? id)))
       (couchdb-request config :get
         :command (str (utils/uri-encode id) (utils/map-to-query-str query-params-map))))))

(defn delete-document
  "Takes a document and deletes it from the database."
  [document]
  (check-and-use-document document
    (couchdb-request config :delete)))

(defmulti copy-document
  "Copies the provided document (or the document with the given string id)
   to the given new id.  If the destination id identifies an existing
   document, then a document map (with up-to-date :_id and :_rev slots)
   must be provided as a destination argument to avoid a 409 Conflict."
  (fn [src dest]
    (if (map? dest) :map :string)))

(defmethod copy-document :string
  [src dest]
  (if-let [src-doc (if (map? src)
                     src
                     {:_id src})]
    (check-and-use-document src-doc
      (couchdb-request config :copy :headers {"Destination"
                                              dest}))))

(defmethod copy-document :map
  [src dest]
  (copy-document src (doc->rev-query-string dest)))

(defmulti update-document
  "Takes document and a map and merges it with the original. When a function
and a vector of keys are supplied as the second and third argument, the
value of the keys supplied are upadated with the result of the function of
their values (see: #'clojure.core/update-in)."
  (fn [& args]
    (let [targ (second args)]
      (cond (fn? targ) :fn
            (map? targ) :map
            :else (throw (IllegalArgumentException.
                          "A map or function is needed to update a document."))))))

(defmethod update-document :fn
  [document update-fn update-keys]
  (let [updated-document      (update-in document update-keys update-fn)
        updated-document-meta (check-and-use-document document
                                (couchdb-request config :put :data updated-document))]
    (if updated-document-meta
      (assoc updated-document :_rev (updated-document-meta :rev)))))

(defmethod update-document :map
  [document merge-map]
  (let [updated-document      (merge document merge-map)
        updated-document-meta (check-and-use-document document
                                (couchdb-request config :put :data updated-document))]
    (if updated-document-meta
      (assoc updated-document :_rev (updated-document-meta :rev)))))

(defn get-all-documents-meta
  "Returns the meta (_id and _rev) of all documents in a database. By adding 
   the key ':include_docs' with a value of true to the optional query params map
   you can also get the full documents, not just their meta. Also takes an optional
   second map of {:key [keys]} to be POSTed.
   (see: http://bit.ly/gxObh)."
  ([]
     (get-all-documents-meta {} {}))
  ([query-params-map]
     (get-all-documents-meta query-params-map {}))
  ([query-params-map post-data-map]
     (couchdb-request config
       (if (empty? post-data-map) :get :post)
       :command (str "_all_docs" (utils/map-to-query-str query-params-map))
       :data (if (empty? post-data-map) nil post-data-map))))

(defn save-view
  "Create a design document used for database queries."
  [design-document-name view-key view-server-map]
  (let [design-doc-id (str "_design/" design-document-name)]
    (if-let [design-doc (get-document design-doc-id)]
      (update-document design-doc (update-in design-doc [:views] #(assoc % view-key view-server-map)))
      (create-document {:language (config :language) :views (hash-map view-key view-server-map)} design-doc-id))))

(defn get-view
  "Get documents associated with a design document. Also takes an optional map
   for querying options, and a second map of {:key [keys]} to be POSTed.
   (see: http://bit.ly/gxObh)."
  ([design-document view-key]
     (get-view design-document view-key {} {}))
  ([design-document view-key query-params-map]
     (get-view design-document view-key query-params-map {}))
  ([design-document view-key query-params-map post-data-map]
     (couchdb-request config
       (if (empty? post-data-map) :get :post)
       :command (str "_design/" design-document "/_view/" (name view-key)
                  (utils/map-to-query-str query-params-map))
       :data (if (empty? post-data-map) nil post-data-map))))

(defn ad-hoc-view
  "One-off queries (i.e. views you don't want to save in the CouchDB database). Ad-hoc
   views should be used during development. Also takes an optional map for querying
   options (see: http://bit.ly/gxObh)."
  ([map-reduce-fns-map]
     (ad-hoc-view map-reduce-fns-map {}))
  ([map-reduce-fns-map query-params-map]
     (couchdb-request config :post 
       :command (str "_temp_view" (utils/map-to-query-str query-params-map))
       :data (if-not (contains? map-reduce-fns-map :language)
               (assoc map-reduce-fns-map :language (config :language))
               map-reduce-fns-map))))

(defn save-filter
  "Create a filter for use with CouchDB change notifications API via 'watch-changes'." 
  [design-document-name view-server-map]
  (let [design-doc-id (str "_design/" design-document-name)]
    (if-let [design-doc (get-document design-doc-id)]
      (update-document design-doc (update-in design-doc [:filters] #(merge %1 view-server-map)))
      (create-document {:language (config :language) :filters view-server-map} design-doc-id))))

(defn bulk-update
  "Takes a vector of documents (maps) and inserts or updates (if \"_id\" and \"_rev\" keys
   are supplied in a document) them with in a single request."
  ([documents-vector]
     (bulk-update documents-vector nil nil))
  ([documents-vector update-map]
     (bulk-update documents-vector update-map nil))
  ([documents-vector update-map options-map]
     (couchdb-request config :post
       :command "_bulk_docs"
       :data (merge {:docs (if update-map 
                             (map #(merge % update-map) documents-vector) 
                             documents-vector)} options-map))))

(defn update-attachment
  "Takes a document, file (either a string path to the file, a java.io.File object, or an InputStream)
   and optionally, a new file name in lieu of the file name of the file argument and a mime type,
   then inserts (or updates if the file name of the attachment already exists in the document)
   the file as an attachment to the document."
  [document attachment & [file-key mime-type]]
  (let [file (cond (string? attachment) (java.io.File. attachment)
               (instance? java.io.File attachment) attachment)
        stream (cond
                 file (-> file java.io.FileInputStream. java.io.BufferedInputStream.)
                 (instance? java.io.InputStream attachment) attachment
                 :else (throw (IllegalArgumentException.
                                "Path string, java.io.File, or InputStream object is expected.")))
        file-name (or file-key (and file (.getName file))
                    (throw (IllegalArgumentException. "Must provide a file-key if using InputStream as attachment data.")))]
    (check-and-use-document document
      (couchdb-request config :put
        :command (if (keyword? file-name)
                   (name file-name) file-name)
        :data stream
        :data-type (or mime-type (and file (utils/get-mime-type file)))))))

(defn get-attachment
  "Returns an InputStream reading the named attachment to the specified/provided document,
   or nil if the document or attachment does not exist.
 
   Hint: use the copy or to-byte-array fns in duck-streams to easily redirect the result."
  [document-or-id attachment-name]
  (let [document        (if (map? document-or-id) document-or-id (get-document document-or-id))
        attachment-name (if (keyword? attachment-name)
                          (name attachment-name)
                          attachment-name)]
    (when (-?> document :_attachments (get (keyword attachment-name)))
      (check-and-use-document document
        (couchdb-request (assoc config :read-json-response false) :get :command attachment-name)))))

(defn delete-attachment
  "Deletes an attachemnt from a document."
  [document file-name]
  (check-and-use-document document
    (couchdb-request config :delete :command file-name)))

(defn get-attachment
  "Returns an InputStream reading the named attachment to the specified/provided document,
   or nil if the document or attachment does not exist.

   Hint: use the copy or to-byte-array fns in c.c.io to easily redirect the result."
  [doc-or-id attachment-name]
  (let [doc (if (map? doc-or-id) doc-or-id (get-document doc-or-id))
        attachment-name (if (keyword? attachment-name)
                          (name attachment-name)
                          attachment-name)]
    (when (-?> doc :_attachments (get (keyword attachment-name)))
      (check-and-use-document doc
        (couchdb-request (assoc config :read-json-response false) :get :command attachment-name)))))