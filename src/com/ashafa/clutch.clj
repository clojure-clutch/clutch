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
  (:require [com.ashafa.clutch [utils :as utils]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.contrib.http.agent :as h]
            [cemerick.url :as url]
            clojure.string)
  (:use com.ashafa.clutch.http-client
        (clojure.contrib core def))
  (:import (java.io File FileInputStream BufferedInputStream InputStream ByteArrayOutputStream)
           (java.net URL))
  (:refer-clojure :exclude (conj! assoc! dissoc!)))

(def ^{:private true} highest-supported-charcode 0xfff0)

(def ^{:doc "A very 'high' unicode character that can be used
              as a wildcard suffix when querying views."}
  ; \ufff0 appears to be the highest character that couchdb can support
  ; discovered experimentally with v0.10 and v0.11 ~March 2010
  ; now officially documented at http://wiki.apache.org/couchdb/View_collation
  wildcard-collation-string (str (char highest-supported-charcode)))

(def ^{:private true} watched-databases (ref {}))

(def ^{:dynamic true :private true} *database* nil)

(defn- with-db*
  [f]
  (fn [& [maybe-db & rest :as args]]
    (if (and (thread-bound? #'*database*)
             (not (identical? maybe-db *database*)))
      (apply f *database* args)
      (apply f (utils/url maybe-db) rest))))

(defmacro ^{:private true} defdbop
  "Same as defn, but wraps the defined function in another that transparently
   allows for dynamic or explicit application of database configuration as well
   as implicit coercion of the first `db` argument to a URL instance."
  [name & body]
  `(do
     (defn ~name ~@body)
     (alter-var-root (var ~name) with-db*)
     (alter-meta! (var ~name) update-in [:doc] str
       "\n\n  When used within the dynamic scope of `with-db`, the initial `db`"
       "\n  argument is automatically provided.")))

(defdbop couchdb-info
  "Returns information about a CouchDB instance."
  [db]
  (couchdb-request :get (utils/server-url db)))

(defdbop all-databases
  "Returns a list of all databases on the CouchDB server."
  [db]
  (couchdb-request :get (url/url db "/_all_dbs")))

(defdbop create-database
  [db]
  (couchdb-request :put db)
  db)

(defdbop database-info
  [db]
  (when-let [info (couchdb-request :get db)]
    (merge info
           (when-let [watchers (@watched-databases (str db))]
             {:watchers (keys watchers)}))))

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
    (url/url tgtdb "/_replicate")
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
  (let [last-update (:update_seq (database-info db))
        options (merge {:heartbeat 30000 :feed "continuous"} options)
        options (if (:since options)
                  options
                  (assoc options :since last-update))
        db-url-key (str db)]
    (when last-update
      (dosync
       (let [uid     (str (java.util.UUID/randomUUID))
             watcher {:uid        uid
                      :http-agent (h/http-agent
                                    (-> (url/url db "_changes") (assoc :query options) str)
                                    :method "GET"
                                    :handler (partial watch-changes-handler db-url-key watch-key uid))
                      :callback   callback}]
         (if (@watched-databases db-url-key)
           (alter watched-databases assoc-in [db-url-key watch-key] watcher)
           (alter watched-databases assoc db-url-key {watch-key watcher}))))
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

(def ^{:private true} byte-array-class (Class/forName "[B"))

(defn- attachment-info
  ([{:keys [data filename mime-type]}] (attachment-info data filename mime-type))
  ([data filename mime-type]
    (let [data (if (string? data)
                 (File. ^String data)
                 data)
          check (fn [k v]
                  (if v v
                    (throw (IllegalArgumentException.
                             (str k " must be provided if attachment data is an InputStream or byte[]")))))]
      (cond
        (instance? File data)
        [(-> ^File data FileInputStream. BufferedInputStream.)
         (or filename (.getName ^File data))
         (or mime-type (utils/get-mime-type data))]
        
        (instance? InputStream data)
        [data (check :filename filename) (check :mime-type mime-type)]
        
        (= byte-array-class (class data))
        [(java.io.ByteArrayInputStream. data) (check :filename filename) (check :mime-type mime-type)]
        
        :default
        (throw (IllegalArgumentException. (str "Cannot handle attachment data of type " (class data))))))))

(defn- to-byte-array
  [input]
  (if (= byte-array-class (class input))
    input
    ; make sure streams are closed so we don't hold locks on files on Windows
    (with-open [^InputStream input input]
      (let [barr (make-array Byte/TYPE 1024)
            out (ByteArrayOutputStream.)]
        (loop []
          (let [size (.read input barr)]
            (when (pos? size)
              (do (.write out barr 0 size)
                (recur)))))
        (.toByteArray out)))))

(defdbop put-document
  [db document & {:keys [id attachments request-params]}]
  (let [document (merge document
                        (when id {:_id id})
                        (when (seq attachments)
                          (->> attachments
                            (map #(if (map? %) % {:data %}))
                            (map attachment-info)
                            (reduce (fn [m [data filename mime]]
                                      (assoc m (keyword filename)
                                        {:content_type mime
                                         :data (-> data
                                                 to-byte-array
                                                 org.apache.commons.codec.binary.Base64/encodeBase64String)}))
                                    {})
                            (hash-map :_attachments))))
        result (couchdb-request
                 (if (:_id document) :put :post)
                 (assoc (utils/url db (:_id document))
                   :query request-params)
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
  (couchdb-request :get
    (-> (url/url db id)
      (assoc :query get-params))))

(defn- document?
  [x]
  (and (map? x) (:_id x) (:_rev x)))

(defn- document-url
  [database-url document]
  (when-not (:_id document)
    (throw (IllegalArgumentException. "A valid document with an :_id slot is required.")))
  (let [with-id (utils/url database-url (:_id document))]
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
    (put-document db document)))

(defdbop configure-view-server
  "Sets the query server exec string for views written in the specified :language
   (\"clojure\" by default).  This is intended to be
   a REPL convenience function; see the Clutch README for more info about setting
   up CouchDB to be Clutch-view-server-ready in general terms."
  [db exec-string & {:keys [language] :or {language "clojure"}}]
  (couchdb-request :put
    (url/url db "/_config/query_servers/" (-> language name url/url-encode))
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
  (let [[view-opts options] (if (map? options)
                             [{:language (or (:language options) :javascript)
                               :code-version (:code-version options)}
                              (dissoc options :language :code-version)]
                             [{:language options}])]
    [{:language (:language (view-transformer (:language view-opts)))
      :code-version (:code-version view-opts)}
     `(#'map-leaves ((:compiler (view-transformer ~(:language view-opts))) ~options) '~fns)]))

(defdbop save-design-document
  "Create/update a design document containing functions used for database
   queries/filtering/validation/etc."
  [db fn-type design-document-name [view-opts view-server-fns]]
  (let [design-doc-id (str "_design/" design-document-name)
        code-version (:code-version view-opts)
        ddoc- {fn-type view-server-fns
               :language (name (:language view-opts))}
        ddoc (if code-version
               (assoc ddoc- :code-version code-version)
               ddoc-)]
    (if-let [design-doc (get-document db design-doc-id)]
      (if (or (not (:code-version design-doc))
                (and code-version
                     (:code-version design-doc)
                     (> code-version (:code-version design-doc))))
        (update-document db design-doc merge ddoc)
        design-doc)
      (put-document db (assoc ddoc :_id design-doc-id)))))

(defdbop save-view
  "Create or update a design document containing views used for database queries."
  [db & args]
  (apply save-design-document db :views args))

(defdbop save-filter
  "Create a filter for use with CouchDB change notifications API via 'watch-changes'."
  [db & args]
  (apply save-design-document db :filters args))

(defn- get-view*
  "Get documents associated with a design document. Also takes an optional map
   for querying options, and a second map of {:key [keys]} to be POSTed.
   (see: http://wiki.apache.org/couchdb/HTTP_view_API)."
  [db path-segments & [query-params-map post-data-map]]
  (view-request
    (if (empty? post-data-map) :get :post)
    (assoc (apply utils/url db path-segments)
           :query (into {} (for [[k v] query-params-map]
                             [k (if (#{"key" "keys" "startkey" "endkey"} (name k))
                                  (json/json-str v)
                                  v)])))
    :data (when (seq post-data-map) post-data-map)))

(defdbop get-view
  "Get documents associated with a design document. Also takes an optional map
   for querying options, and a second map of {:key [keys]} to be POSTed.
   (see: http://wiki.apache.org/couchdb/HTTP_view_API)."
  [db design-document view-key & [query-params-map post-data-map :as args]]
  (apply get-view* db
         ["_design" design-document "_view" (name view-key)]
         args))

(defdbop all-documents
  "Returns the meta (_id and _rev) of all documents in a database. By adding
   the key ':include_docs' with a value of true to the optional query params map
   you can also get the full documents, not just their meta. Also takes an optional
   second map of {:keys [keys]} to be POSTed.
   (see: http://wiki.apache.org/couchdb/HTTP_view_API)."
  [db & [query-params-map post-data-map :as args]]
  (apply get-view* db ["_all_docs"] args))

(defdbop ad-hoc-view
  "One-off queries (i.e. views you don't want to save in the CouchDB database). Ad-hoc
   views should be used only during development. Also takes an optional map for querying
   options (see: http://wiki.apache.org/couchdb/HTTP_view_API)."
  [db [view-opts view-server-fns] & [query-params-map]]
  (get-view* db ["_temp_view"]  query-params-map (into {:language (:language view-opts)} view-server-fns)))

(defdbop bulk-update
  "Takes a sequential collection of documents (maps) and inserts or updates (if \"_id\" and \"_rev\" keys
   are supplied in a document) them with in a single request.

   Optional keyword args may be provided, and are sent along with the documents
   (e.g. for \"all-or-nothing\" semantics, etc)."
  [db documents & {:as options}]
  (couchdb-request :post
    (utils/url db "_bulk_docs")
    :data (assoc options :docs documents)))

(defdbop put-attachment
  "Updates (or creates) the attachment for the given document.  `data` can be a string path
   to a file, a java.io.File, a byte array, or an InputStream.
   
   If `data` is a byte array or InputStream, you _must_ include the following otherwise-optional
   kwargs:

       :filename — name to be given to the attachment in the document
       :mime-type — type of attachment data

   These are derived from a file path or File if not provided.  (Mime types are derived from 
   filename extensions; see com.ashafa.clutch.utils/get-mime-type for determining mime type
   yourself from a File object.)"
  [db document data & {:keys [filename mime-type]}]
  (let [[stream filename mime-type] (attachment-info data filename mime-type)]
    (couchdb-request :put
      (-> db
        (document-url document)
        (utils/url (name filename)))
      :data stream
      :data-type mime-type)))

(defdbop delete-attachment
  "Deletes an attachemnt from a document."
  [db document file-name]
  (couchdb-request :delete (utils/url (document-url db document) file-name)))

(defdbop get-attachment
  "Returns an InputStream reading the named attachment to the specified/provided document
   or nil if the document or attachment does not exist.

   Hint: use the copy or to-byte-array fns in c.c.io to easily redirect the result."
  [db doc-or-id attachment-name]
  (let [doc (if (map? doc-or-id) doc-or-id (get-document db doc-or-id))
        attachment-name (if (keyword? attachment-name)
                          (name attachment-name)
                          attachment-name)]
    (when (-?> doc :_attachments (get (keyword attachment-name)))
      (couchdb-request :get
                       (-> (document-url db doc)
                         (utils/url attachment-name)
                         (assoc :read-json-response false))))))

(defmacro with-db
  "Takes a URL, database name (useful for localhost only), or an instance of
   com.ashafa.clutch.utils.URL.  That value is used to configure the subject
   of all of the operations within the dynamic scope of body of code."
  [database & body]
  `(binding [*database* (utils/url ~database)]
     ~@body))


(defprotocol CouchOps
  "Defines side-effecting operations on a CouchDB database.
   All operations return the CouchDB database reference —
   with the return value from the underlying clutch function
   added to its :result metadata — for easy threading and
   reduction usage.
   (EXPERIMENTAL!)"
  (create! [this] "Ensures that the database exists, and returns the database's meta info.")
  (conj! [this document]
         "PUTs a document into CouchDB. Accepts either a document map (using an :_id value
          if present as the document id), or a vector/map entry consisting of a
          [id document-map] pair.")
  (assoc! [this id document]
          "PUTs a document into CouchDB. Equivalent to (conj! couch [id document]).")
  (dissoc! [this id-or-doc]
           "DELETEs a document from CouchDB. Uses a given document map's :_id and :_rev
            if provided; alternatively, if passed a string, will blindly attempt to 
            delete the current revision of the corresponding document."))

(defn- with-result-meta
  [couch result]
  (vary-meta couch assoc :result result))

(deftype CouchDB [url meta]
  com.ashafa.clutch.CouchOps
  (create! [this] (with-result-meta this (get-database url)))
  (conj! [this doc]
    (let [[id doc] (cond
                     (map? doc)  [(:_id doc) doc]
                     (or (vector? doc) (instance? java.util.Map$Entry)) doc)]
      (->> (put-document url doc :id id)
        (fail-on-404 url)
        (with-result-meta this))))
  (assoc! [this id document] (conj! this [id document]))
  (dissoc! [this id]
    (if-let [d (if (document? id)
                   id
                   (this id))]
      (with-result-meta this (delete-document url d))
      (with-result-meta this nil)))
  
  clojure.lang.ILookup
  (valAt [this k] (get-document url k))
  (valAt [this k default] (or (.valAt this k) default))
  
  clojure.lang.Counted
  (count [this] (->> (database-info url) (fail-on-404 url) :doc_count))
  
  clojure.lang.Seqable
  (seq [this]
    (->> (all-documents url {:include_docs true})
      (map :doc)
      (map #(clojure.lang.MapEntry. (:_id %) %))))
  
  clojure.lang.IFn
  (invoke [this key] (.valAt this key))
  (invoke [this key default] (.valAt this key default))
  
  clojure.lang.IMeta
  (meta [this] meta)
  clojure.lang.IObj
  (withMeta [this meta] (CouchDB. url meta)))

(defn couch
  "Returns an instance of an implementation of CouchOps.
   (EXPERIMENTAL!)"
  ([url] (CouchDB. url nil))
  ([url meta] (CouchDB. url meta)))