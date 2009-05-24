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
  (:require [com.ashafa.clutch.utils :as utils])
  (:use com.ashafa.clutch.http-client))


(declare config)

;;; Default Clutch configuration...
(defonce *config* (ref {:host "localhost"
                        :port 5984
                        :language "javascript"}))

(defn set-couchdb-config!
  "Takes a map with a custom CouchDB configuration as follows: 
        {:host     <ip (defaults to \"localhost\")>
         :port     <port (defaults to 5984)>
         :language <language view server to use (see: README)>
         :username <username (if http authentication is enabled)>
         :password <password (if http authentication is enabled)>}."
  [config-map]
  (dosync (alter *config* merge config-map)))

(defmacro #^{:private true} check-and-use-document
  "Private macro for apis that deal with the current revision of
   a doocument."
  [doc & body]
  `(if-let [id# (~doc :_id)]
     (binding [config 
               (assoc config :database 
                   (str (config :database) "/" id# "?rev=" (:_rev ~doc)))]
       (do ~@body))
     (throw 
      (IllegalArgumentException. "A valid document is required."))))

(defmacro with-clj-view-server
  "Takes one or two functions and returns a map-reduce map (with the functions
   serialized as strings) used by the Clojure view server."
  ([map-form]
     `(with-clj-map-reduce ~(pr-str map-form) nil))
  ([map-form reduce-form]
     (let [map-reduce-map {:map (if (string? map-form) map-form (pr-str map-form))}]
       (if reduce-form
          (assoc map-reduce-map :reduce (pr-str reduce-form))
          map-reduce-map))))

(defmacro with-db
  "Takes a string (database name) and forms. It binds the database name to config 
   and then executes the body."
  [database & body]
  `(binding [config (assoc @*config* :database ~database)]
     (do ~@body)))

(defn couchdb-info
  "Returns the CouchDB version info."
  []
  (couchdb-request @*config* :get))

(defn all-databases
  "Returns a list of all databases on the CouchDB server."
  []
  (couchdb-request @*config* :get "_all_dbs"))

(defn create-database 
  "Takes a string and creates a database using the string as the name."
  [name]
  (couchdb-request @*config* :put name))

(defn delete-database
  "Takes a database name and deletes the corresponding database."
  [name]
  (couchdb-request @*config* :delete name))

(defn database-info
  "Takes a database name and returns the meta information about the database."
  [name]
  (couchdb-request @*config* :get name))

(defn create-document
  "Takes a map and creates a document with an auto generated id, returns the id
   and revision in a map."
  ([document-map]
     (couchdb-request config :post nil document-map))
  ([id document-map]
     (couchdb-request config :put id document-map)))

(defn get-document
  "Takes an argument and returns a document (as a map) with that id."
  [id]
  (if (and id (not (empty? id))) (couchdb-request config :get id)))

(defn delete-document 
  "Takes a document (a map with :_id and :_rev keywords) and deletes it from the
   database."
  [document]
  (check-and-use-document document
    (couchdb-request config :delete)))

(defmulti update-document
  "Takes document (a map with :_id and :_rev keywords) and a map and merges it
   with the original. When a function and a vector of keys are supplied as the
   second and third argument, the value of the keys supplied are upadated with
   the result of the function of their values (see: #'clojure.core/update-in)."
  (fn [& args] 
    (let [targ (second args)]
      (cond (fn? targ)  :update-fn
            (map? targ) :merge-map
            :else (throw (IllegalArgumentException. 
                          "A map or function is needed to update a document."))))))

(defmethod update-document :update-fn 
  [document update-fn update-keys]
  (check-and-use-document document
    (couchdb-request config :put nil
                       (update-in document update-keys update-fn))))

(defmethod update-document :merge-map 
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
       (if-let [design-doc (get-doc design-doc-id)]
         (update-document design-doc #(assoc % view-key view-server-map) [:views])
         (create-document design-doc-id
                             {:language (config :language)
                              :views (hash-map view-key view-server-map)}))))

(defn get-view
  "Get documents associated with a design document. Also takes an optional map
   for querying options (see: http://bit.ly/gxObh)."
  ([design-document view-key]
     (get-view design-document view-key {}))
  ([design-document view-key query-params-map]
     (couchdb-request config :get 
                      (str "_design/" design-document "/_view/" (name view-key)
                           (utils/map-to-query-str query-params-map)))))

(defn adhoc-view
  "One-off queries (i.e. views you don't want to save in the CouchDB database). Ad-hoc
   views are only good during development. Also takes an optional map for querying
   options (see: http://bit.ly/gxObh)."
  ([map-reduce-fns-map]
     (adhoc-view map-reduce-fns-map {}))
  ([map-reduce-fns-map query-params-map]
     (couchdb-request config :post 
                      (str "_temp_view" (utils/map-to-query-str query-params-map))
                      (if-not (contains? map-reduce-fns-map :language)
                        (assoc map-reduce-fns-map :language (config :language))
                        map-reduce-fns-map))))

(defn bulk-insert-update
  "Takes a vector of documents (maps) and inserts or updates (if \"_id\" and \"_rev\" keys
   are supplied in a document) them with a single CouchDB request."
  ([documents-vector]
     (bulk-insert-update documents-vector nil nil))
  ([documents-vector update-map]
     (bulk-insert-update documents-vector update-map nil))
  ([documents-vector update-map options-map]
     (couchdb-request config :post "_bulk_docs"
                      (merge {:docs (if update-map 
                                      (map #(merge % update-map) documents-vector) 
                                      documents-vector)}
                             options-map))))