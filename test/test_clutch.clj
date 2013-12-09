(ns ^{:author "Tunde Ashafa"}
  test-clutch
  (:require (com.ashafa.clutch [http-client :as http-client]
                               [utils :as utils])
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:use com.ashafa.clutch
        [cemerick.url :only (url)]
        [slingshot.slingshot :only (throw+ try+)]
        clojure.set
        clojure.test)
  (:import (java.io File ByteArrayInputStream FileInputStream ByteArrayOutputStream)
           (java.net URL))
  (:refer-clojure :exclude (conj! assoc! dissoc!)))

; Can be e.g.
; "https://username:password@account.cloudant.com" or
;   (assoc (utils/url "localhost")
;     :username "username"
;     :password "password")
(def test-host (or (System/getenv "clutch_url") "http://localhost:5984"))

(println "Testing using Clojure" *clojure-version*
         "on Java" (System/getProperty "java.version")
         "=>>" (-> test-host url (assoc :username nil :password nil) str))
(println "CouchDB server info:" (couchdb-info (-> test-host url str)))

(def resources-path "test")

(def test-docs [{:name  "John Smith"
                 :email "john.smith@test.com"
                 :score 65}
                {:name  "Jane Thompson"
                 :email "jane.thompson@test.com"
                 :score 98}
                {:name  "Robert Jones"
                 :email "robert.jones@example.com"
                 :score 80}
                {:name  "Sarah Parker"
                 :email "sarah.parker@example.com"
                 :score 59}])

(def ^{:private true} to-byte-array @#'com.ashafa.clutch/to-byte-array)

(declare ^{:dynamic true} *test-database*)

(defn test-database-name
  [test-name]
  (str "test-db-" (str/replace (str test-name) #"[^$]+\$([^@]+)@.*" "$1")))

(defn test-database-url
  [db-name]
  (utils/url (utils/url test-host) db-name))

(defmacro defdbtest [name & body]
  `(deftest ~name
     (binding [*test-database* (get-database (test-database-url (test-database-name ~name)))]
       (try
        (with-db *test-database* ~@body)
        (finally
          (delete-database *test-database*))))))

(deftest check-couchdb-connection
  (is (= "Welcome" (:couchdb (couchdb-info (test-database-url nil))))))

(deftest get-list-check-and-delete-database
  (let [name "clutch_test_db"
        url (test-database-url name)
        *test-database* (get-database url)]
    (is (= name (:db_name *test-database*)))
    (is ((set (all-databases url)) name))
    (is (= name (:db_name (database-info url))))
    (is (:ok (delete-database url)))
    (is (nil? ((set (all-databases url)) name)))))

(deftest database-name-escaping
  (let [name (test-database-name "foo_$()+-/bar")
        url (test-database-url name)]
    (try
      (let [dbinfo (get-database url)]
        (is (= name (:db_name dbinfo))))
      (put-document url {:_id "a" :b 0})
      (is (= 0 (:b (get-document url "a"))))
      (delete-document url (get-document url "a"))
      (is (nil? (get-document url "a")))
      (finally
        (delete-database url)))))

(defn- valid-id-charcode?
  [code]
  (cond
    ; c.c.json doesn't cope well with the responses provided when CR or LF are included
    (#{10 13} code) false
    ; D800–DFFF only used in surrogate pairs, invalid anywhere else (couch chokes on them)
    (and (>= code 0xd800) (<= code 0xdfff)) false
    (> code @#'com.ashafa.clutch/highest-supported-charcode) false
    :else true))

; create a document containing each of the 65K chars in unicode BMP.
; this ensures that utils/id-encode is doing what it should and that we aren't screwing up
; encoding issues generally (which are easy regressions to introduce)
(defdbtest test-docid-encoding
  ; doing a lot of requests here -- the test is crazy-slow if delayed_commit=false,
  ; so let's use the iron we've got
  (doseq [x (range 0xffff)
          :when (valid-id-charcode? x)
          :let [id (str "a" (char x)) ; doc ids can't start with _, so prefix everything
                id-desc (str x " " id)]]
    (try
      (is (= id (:_id (put-document {:_id id}))) id-desc)
      (let [doc (get-document id)]
        (is (= {} (dissoc-meta doc)))
        (delete-document doc)
        (is (nil? (get-document id))))
      (catch Exception e
        (is false (str "Error for " id-desc ": " (.getMessage e)))))))

(defdbtest create-a-document
  (let [document (put-document (first test-docs))]
    (are [k] (contains? document k)
         :_id :_rev)))

(defdbtest create-a-document-with-id
  (let [document (put-document (first test-docs) :id "my_id")]
    (is (= "my_id" (document :_id)))))

(defdbtest test-exists
  (put-document {:_id "foo" :a 5})
  (is (not (document-exists? "bar")))
  (is (document-exists? "foo")))

(defrecord Foo [a])

(defdbtest create-with-record
  (let [rec (put-document (Foo. "bar") :id "docid")]
    (is (instance? Foo rec)))
  (is (= "bar" (-> "docid" get-document :a))))

(defdbtest get-a-document
  (let [created-document (put-document (nth test-docs 2))
        fetched-document (get-document (created-document :_id))]
    (are [x y z] (= x y z)
         "Robert Jones" (created-document :name) (fetched-document :name)
         "robert.jones@example.com" (created-document :email) (fetched-document :email)
         80 (created-document :score) (fetched-document :score))))

(defdbtest get-a-document-revision
  (let [created-document (put-document (nth test-docs 2))
        updated-doc (update-document (assoc created-document :newentry 1))
        fetched-document (get-document (:_id created-document)
                                       :rev (:_rev created-document))]
    (are [x y z] (= x y z)
         "Robert Jones" (created-document :name) (fetched-document :name)
         "robert.jones@example.com" (created-document :email) (fetched-document :email)
         80 (:score created-document) (:score fetched-document)
         nil (:newentry created-document) (:newentry fetched-document))
    (is (= 1 (:newentry updated-doc)))))

(defmacro failing-request
  [expected-status & body]
  `(binding [http-client/*response* nil]
    (try+
      ~@body
      (assert false)
      (catch identity ex#
        (is (== ~expected-status (:status ex#)))
        (is (map? http-client/*response*))))))

(defdbtest verify-response-code-access
  (put-document (first test-docs) :id "some_id")
  (failing-request 409 (put-document (first test-docs) :id "some_id")))

(defdbtest update-a-document
  (let [id (:_id (put-document (nth test-docs 3)))]
    (update-document (get-document id) {:email "test@example.com"})
    (is (= "test@example.com" (:email (get-document id)))))
  (testing "no update map or fn"
    (let [id (:_id (put-document (nth test-docs 3)))]
      (update-document (merge (get-document id) {:email "test@example.com"}))
      (is (= "test@example.com" (:email (get-document id)))))))

(defdbtest update-a-document-with-a-function
  (let [id (:_id (put-document (nth test-docs 2)))]
    (update-document (get-document id) update-in [:score] + 3)
    (is (= 83 (:score (get-document id))))))

(defdbtest update-with-updated-map
  (-> (nth test-docs 2)
    (put-document :id "docid")
    (assoc :a "bar")
    update-document)
  (is (= "bar" (-> "docid" get-document :a))))

(defdbtest update-with-record
  (let [rec (-> (Foo. "bar")
              (merge (put-document {} :id "docid"))
              update-document)]
    (is (instance? Foo rec)))
  (is (= "bar" (-> "docid" get-document :a))))

(defdbtest delete-a-document
  (put-document (second test-docs) :id "my_id")
  (is (get-document "my_id"))
  (is (true? (:ok (delete-document (get-document "my_id")))))
  (is (nil? (get-document "my_id"))))

(defdbtest copy-a-document
  (let [doc (put-document (first test-docs) :id "src")]
    (copy-document "src" "dst")
    (copy-document doc "dst2")
    (is (= (dissoc-meta doc)
           (-> "dst" get-document dissoc-meta)
           (-> "dst2" get-document dissoc-meta)))))

(defdbtest copy-document-overwrite
  (let [doc (put-document (first test-docs) :id "src")
        overwrite (put-document (second test-docs) :id "overwrite")]
    (copy-document doc overwrite)
    (is (= (dissoc-meta doc) (dissoc-meta (get-document "overwrite"))))))

(defdbtest copy-document-attachments
  (let [doc (put-document (first test-docs) :id "src")
        file (File. (str resources-path "/couchdb.png"))
        doc (put-attachment doc file :filename :image)
        doc (-> doc :id get-document)]
    (copy-document "src" "dest")
    (let [copy (get-document "dest")
          copied-attachment (get-attachment copy :image)]
      (is (= (dissoc-meta doc) (dissoc-meta copy)))
      (is (= (-> file to-byte-array seq)
             (-> copied-attachment to-byte-array seq))))))

(defdbtest copy-document-fail-overwrite
  (put-document (first test-docs) :id "src")
  (put-document (second test-docs) :id "overwrite")
  (failing-request 409 (copy-document "src" "overwrite")))

(defdbtest get-all-documents-with-query-parameters
  (bulk-update test-docs)
  (let [all-documents-descending (all-documents {:include_docs true :descending true})
        all-documents-ascending  (all-documents {:include_docs true :descending false})]
    (are [results] (= 4 (:total_rows (meta results)))
         all-documents-descending
         all-documents-ascending)
    (are [name] (= "Sarah Parker" name)
         (-> all-documents-descending first :doc :name)
         (-> all-documents-ascending last :doc :name))))

(defdbtest get-all-documents-with-post-keys
  (doseq [[n x] (keep-indexed vector test-docs)]
    (put-document x :id (str (inc n))))
  (let [all-documents               (all-documents {:include_docs true} {:keys ["1" "2"]})
        all-documents-matching-keys all-documents]
    (is (= ["John Smith" "Jane Thompson"]
           (map #(-> % :doc :name) all-documents-matching-keys)))
    (is (= 4 (:total_rows (meta all-documents))))))

(defdbtest bulk-update-new-documents
  (bulk-update test-docs)
  (is (= 4 (:total_rows (meta (all-documents))))))

(defdbtest bulk-update-documents
  (bulk-update test-docs)
  (bulk-update (->> (all-documents {:include_docs true})
                 (map :doc)
                 (map #(assoc % :updated true))))
  (is (every? true? (map #(-> % :doc :updated) (all-documents {:include_docs true})))))

(defdbtest inline-attachments
  (let [clojure-img-file (str resources-path "/clojure.png")
        couchdb-img-file (str resources-path "/couchdb.png")
        couch-filename :couchdb.png
        bytes-filename :couchdbbytes.png
        created-document (put-document (nth test-docs 3)
                           :attachments [clojure-img-file
                                         {:data (to-byte-array (FileInputStream. couchdb-img-file))
                                          :data-length (-> couchdb-img-file File. .length)
                                          :filename bytes-filename :mime-type "image/png"}
                                         {:data (FileInputStream. couchdb-img-file)
                                          :data-length (-> couchdb-img-file File. .length)
                                          :filename couch-filename :mime-type "image/png"}])
        fetched-document (get-document (created-document :_id))]
    (are [attachment-keys] (= #{:clojure.png couch-filename bytes-filename} attachment-keys)
         (set (keys (created-document :_attachments)))
         (set (keys (fetched-document :_attachments))))
    (are [doc file-key] (= "image/png" (-> doc :_attachments file-key :content_type))
         created-document :clojure.png
         fetched-document :clojure.png
         created-document couch-filename
         fetched-document couch-filename
         created-document bytes-filename
         fetched-document bytes-filename)
    (are [path file-key] (= (.length (File. path)) (-> fetched-document :_attachments file-key :length))
         clojure-img-file :clojure.png
         couchdb-img-file couch-filename
         couchdb-img-file bytes-filename)))

#_(defdbtest standalone-attachments
  (let [document (put-document (first test-docs))
        path (str resources-path "/couchdb.png")
        filename-with-space (keyword "couchdb - image2")
        updated-document-meta (put-attachment document path :filename :couchdb-image)
        updated-document-meta (put-attachment (assoc document :_rev (:rev updated-document-meta))
                                (FileInputStream. path)
                                :filename filename-with-space
                                :mime-type "image/png"
                                :data-length (-> path File. .length))
        updated-document-meta (put-attachment (assoc document :_rev (:rev updated-document-meta))
                                (to-byte-array (FileInputStream. path))
                                :filename :bytes-image :mime-type "image/png"
                                :data-length (-> path File. .length))

        _ (.println System/out (pr-str (String.
                              (com.ashafa.clutch.http-client/couchdb-request :get
                        (-> (cemerick.url/url *test-database* (updated-document-meta :id))
                          (assoc :query {:attachments true}
                                 :as :byte-array)))
                              "UTF-8")))
        _ (do (flush) (Thread/sleep 5000))
        #_#_document-with-attachments (get-document (updated-document-meta :id) :attachments true)]
    #_((is (= #{:couchdb-image filename-with-space :bytes-image} (set (keys (:_attachments document-with-attachments)))))
    (is (= "image/png" (-> document-with-attachments :_attachments :couchdb-image :content_type)))
    (is (contains? (-> document-with-attachments :_attachments :couchdb-image) :data))

    (is (= (-> document-with-attachments :_attachments :couchdb-image (select-keys [:data :content_type :length]))
           (-> document-with-attachments :_attachments filename-with-space (select-keys [:data :content_type :length]))
           (-> document-with-attachments :_attachments :bytes-image (select-keys [:data :content_type :length]))))

    (is (thrown? IllegalArgumentException (put-attachment document (Object.))))
    (is (thrown? IllegalArgumentException (put-attachment document (ByteArrayInputStream. (make-array Byte/TYPE 0))))))))

(defdbtest stream-attachments
  (let [document                  (put-document (nth test-docs 3))
        updated-document-meta     (put-attachment document (str resources-path "/couchdb.png")
                                    :filename :couchdb-image
                                    :mime-type "other/mimetype")
        document-with-attachments (get-document (updated-document-meta :id))
        data (to-byte-array (java.io.File. (str resources-path "/couchdb.png")))]
      (is (= "other/mimetype" (-> document-with-attachments :_attachments :couchdb-image :content_type)))
      (is (= (seq data) (-> (get-attachment document-with-attachments :couchdb-image) to-byte-array seq)))))

(deftest replicate-a-database
  (let [source (url test-host "source_test_db")
        target (url test-host "target_test_db")]
    (try
      (get-database source)
      (get-database target)
      (bulk-update source test-docs)
      (replicate-database source target)
      (is (= 4 (:total_rows (meta (all-documents target)))))
      (finally
        (delete-database source)
        (delete-database target)))))

(defn report-change
  [description & forms]
  (doseq [result forms]
    (println (str "Testing changes: '" description "'") (if result "passed" "failed"))))

(defn check-id-changes-test
  [description change-meta]
  (if-not (:last_seq change-meta)
    (report-change description
     (is (= (:id change-meta) "target-id")))))

(defn check-seq-changes-test
  [description change-meta]
  (if-not (:last_seq change-meta)
    (report-change description
     (is (= (:seq change-meta) 1)))))

(defn check-delete-changes-test
  [description change-meta]
  (if (:deleted change-meta)
    (report-change description
     (is (= (:id change-meta) "target-id"))
     (is (= (:seq change-meta) 5)))))

(deftest direct-db-config-usage
  (let [db (test-database-url "direct-db-config-usage")]
    (try
      (create-database db)
      (let [doc (put-document db (first test-docs) :id "foo")]
        (update-document db doc {:a 5})
        (is (= (assoc (first test-docs) :a 5) (dissoc-meta (get-document db "foo")))))
      (finally
        (delete-database db)))))

(deftest multiple-binding-levels
  (let [db1 (test-database-url "multiple-binding-levels")
        db2 (test-database-url "multiple-binding-levels2")]
    (with-db db1
      (try
        (is (= "multiple-binding-levels" (:db_name (get-database))))
        (put-document {} :id "1")
        (with-db db2
          (try
            (is (= "multiple-binding-levels2" (:db_name (get-database))))
            (is (nil? (get-document "1")))
            (let [doc (put-document {} :id "2")]
              (update-document doc {:a 5})
              (is (= {:a 5} (dissoc-meta (get-document "2")))))
            (finally
              (delete-database))))
        (is (nil? (get-document "2")))
        (finally
          (delete-database))))))
