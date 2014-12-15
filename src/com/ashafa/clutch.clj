(ns com.ashafa.clutch
  (:require [com.ashafa.clutch [utils :as utils]]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [cemerick.url :as url]
            clojure.string)
  (:use com.ashafa.clutch.http-client)
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

(def ^{:dynamic true :private true} *database* nil)

(declare couchdb-class)

(defn- with-db*
  [f]
  (fn [& [maybe-db & rest :as args]]
    (let [maybe-db (if (instance? couchdb-class maybe-db)
                     (.url maybe-db)
                     maybe-db)]
      (if (and (thread-bound? #'*database*)
               (not (identical? maybe-db *database*)))
      (apply f *database* args)
      (apply f (utils/url maybe-db) rest)))))

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
  (couchdb-request :get db)
  #_(when-let [info (couchdb-request :get db)]
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

(def ^{:private true} byte-array-class (Class/forName "[B"))

(defn- attachment-info
  ([{:keys [data filename mime-type data-length]}] (attachment-info data data-length filename mime-type))
  ([data data-length filename mime-type]
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
         (.length ^File data)
         (or filename (.getName ^File data))
         (or mime-type (utils/get-mime-type data))]
        
        (instance? InputStream data)
        [data (check :data-length data-length) (check :filename filename) (check :mime-type mime-type)]
        
        (= byte-array-class (class data))
        [(java.io.ByteArrayInputStream. data) (count data)
         (check :filename filename) (check :mime-type mime-type)]
        
        :default
        (throw (IllegalArgumentException. (str "Cannot handle attachment data of type " (class data))))))))

(defn- to-byte-array
  [input]
  (if (= byte-array-class (class input))
    input
    ; make sure streams are closed so we don't hold locks on files on Windows
    (with-open [^InputStream input (io/input-stream input)]
      (let [out (ByteArrayOutputStream.)]
        (io/copy input out)
        (.toByteArray out)))))

(defdbop put-document
  [db document & {:keys [id attachments request-params]}]
  (let [document (merge document
                        (when id {:_id id})
                        (when (seq attachments)
                          (->> attachments
                            (map #(if (map? %) % {:data %}))
                            (map attachment-info)
                            (reduce (fn [m [data data-length filename mime]]
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
  ;; TODO a nil or empty key should probably just throw an exception
  (when (seq (str id))
    (couchdb-request :get
      (-> (utils/url db id)
        (assoc :query get-params)))))

(defdbop document-exists?
  "Returns true if a document with the given key exists in the database."
  [db id]
  ;; TODO a nil or empty key should probably just throw an exception
  (when (seq (str id))
    (= 200 (:status (couchdb-request* :head (utils/url db id))))))

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

(defmethod view-transformer :cljs
  [language]
  (try
    (require 'com.ashafa.clutch.cljs-views)
    ; com.ashafa.clutch.cljs-views defines a method for :cljs, so this
    ; call will land in it
    (view-transformer language)
    (catch Exception e
      (throw (UnsupportedOperationException.
               "Could not load com.ashafa.clutch.cljs-views; ensure ClojureScript and its dependencies are available, and that you're using Clojure >= 1.3.0." e)))))

(defmacro view-server-fns
  [options fns]
  (let [[language options] (if (map? options)
                             [(or (:language options) :javascript) (dissoc options :language)]
                             [options])]
    [(:language (view-transformer language))
     `(#'map-leaves ((:compiler (view-transformer ~language)) ~options) '~fns)]))

(defdbop save-design-document
  "Create/update a design document containing functions used for database
   queries/filtering/validation/etc."
  [db fn-type design-document-name [language view-server-fns]]
  (let [design-doc-id (str "_design/" design-document-name)
        ddoc {fn-type view-server-fns
              :language (name language)}]
    (if-let [design-doc (get-document db design-doc-id)]
      (update-document db design-doc merge ddoc)
      (put-document db (assoc ddoc :_id design-doc-id)))))

(defdbop save-view
  "Create or update a design document containing views used for database queries."
  [db & args]
  (apply save-design-document db :views args))

(defdbop save-filter
  "Create a filter for use with CouchDB change notifications API."
  [db & args]
  (apply save-design-document db :filters args))

(defn- get-view*
  "Get documents associated with a design document. Also takes an optional map
   for querying options, and a second map of {:key [keys]} to be POSTed.
   (see: http://wiki.apache.org/couchdb/HTTP_view_API)."
  [db path-segments & [query-params-map post-data-map]]
  (let [url (assoc (apply utils/url db path-segments)
              :query (into {} (for [[k v] query-params-map]
                                [k (if (#{"key" "keys" "startkey" "endkey"} (name k))
                                     (json/generate-string v)
                                     v)])))]
    (view-request
      (if (empty? post-data-map) :get :post)
      url
      :data (when (seq post-data-map) post-data-map))))

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
  [db [language view-server-fns] & [query-params-map]]
  (get-view* db ["_temp_view"]  query-params-map (into {:language language} view-server-fns)))

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
  [db document data & {:keys [filename mime-type data-length]}]
  (let [[stream data-length filename mime-type] (attachment-info data data-length filename mime-type)]
    (couchdb-request :put
      (-> db
        (document-url document)
        (utils/url (name filename)))
      :data stream
      :data-length data-length
      :content-type mime-type)))

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
    (when (-> doc :_attachments (get (keyword attachment-name)))
      (couchdb-request :get
                       (-> (document-url db doc)
                         (utils/url attachment-name)
                         (assoc :as :stream))))))

;;;; _changes

(defdbop changes
  "Returns a lazy seq of the rows in _changes, as configured by the given options.

   If you want to react to change notifications, you should probably use `change-agent`."
  [db & {:keys [since limit descending feed heartbeat
                timeout filter include_docs style] :as opts}]
  (let [url (url/url db "_changes")
        response (couchdb-request* :get (assoc url :query opts :as :stream))]
    (when-not response
      (throw (IllegalStateException. (str "Database for _changes feed does not exist: " url))))
    (-> response
      :body
      (lazy-view-seq (not= "continuous" feed))
      (vary-meta assoc ::http-resp response))))

(defn- change-agent-config
  [db options]
  (merge {:heartbeat 30000 :feed "continuous"}
         options
         (when-not (:since options)
           {:since (:update_seq (database-info db))})
         {::db db
          ::state :init
          ::last-update-seq nil}))

(defdbop change-agent
  "Returns an agent whose state will very to contain events
   emitted by the _changes feed of the specified database.

   Users are expected to attach functions to the agent using
   `add-watch` in order to be notified of changes.  The
   returned change agent is entirely 'managed', with
   `start-changes` and `stop-changes` controlling its operation
   and sent actions.  If you send actions to a change agent, 
   bad things will likely happen."
  [db & {:as opts}]
  (agent nil :meta {::changes-config (atom (change-agent-config db opts))}))

(defn- run-changes
  [_]
  (let [config-atom (-> *agent* meta ::changes-config)
        config @config-atom]
    (case (::state config)
      :init (let [changes (apply changes (::db config) (flatten (remove (comp namespace key) config)))
                  http-resp (-> changes meta ::http-resp)]
              ; cannot shut down continuous _changes feeds without aborting this
              (assert (-> http-resp :request :http-req))
              (swap! config-atom merge {::seq changes
                                        ::http-resp http-resp
                                        ::state :running})
              (send-off *agent* #'run-changes)
              nil)
      :running (let [change-seq (::seq config)
                     change (first change-seq)
                     last-change-seq (or (:seq change) (:last_seq change))]
                 (send-off *agent* #'run-changes)
                 (when-not (= :stopped (::state @config-atom))
                   (swap! config-atom merge
                          {::seq (rest change-seq)}
                          (when last-change-seq {::last-update-seq last-change-seq})
                          (when-not change {::state :stopped}))
                   change))
      :stopped (-> config ::http-resp :request :http-req .abort))))

(defn changes-running?
  "Returns true only if the given change agent has been started
   (using `start-changes`) and is delivering changes to
   attached watches."
  [^clojure.lang.Agent change-agent]
  (boolean (-> change-agent meta ::state #{:init :running})))

(defn start-changes
  "Starts the flow of changes through the given change-agent.
   All of the options provided to `change-agent` are used to
   configure the underlying _changes feed."
  [change-agent]
  (send-off change-agent #'run-changes))

(defn stop-changes
  "Stops the flow of changes through the given change-agent.
   Change agents can be restarted with `start-changes`."
  [change-agent]
  (swap! (-> change-agent meta ::changes-config) assoc ::state :stopped)
  change-agent)


(defmacro with-db
  "Takes a URL, database name (useful for localhost only), or an instance of
   com.ashafa.clutch.utils.URL.  That value is used to configure the subject
   of all of the operations within the dynamic scope of body of code."
  [database & body]
  `(binding [*database* (utils/url ~database)]
     ~@body))

;;;; CouchDB deftype

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

(def ^{:private true} couchdb-class CouchDB)

(defn couch
  "Returns an instance of an implementation of CouchOps.
   (EXPERIMENTAL!)"
  ([url] (CouchDB. url nil))
  ([url meta] (CouchDB. url meta)))
