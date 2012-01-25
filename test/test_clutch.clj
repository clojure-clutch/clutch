(ns ^{:author "Tunde Ashafa"}
  test-clutch
  (:require (com.ashafa.clutch
              [http-client :as http-client]
              [utils :as utils]
              [view-server :as view-server])
            [clojure.contrib.str-utils :as str]
            [clojure.contrib.io :as io])
  (:use com.ashafa.clutch 
        clojure.test)
  (:import (java.io File ByteArrayInputStream FileInputStream)
           (java.net URL)))

(println "Testing using Clojure" *clojure-version*)

(def resources-path "test")

(def test-document-1 {:name  "John Smith"
                      :email "john.smith@test.com"
                      :score 65})

(def test-document-2 {:name  "Jane Thompson"
                      :email "jane.thompson@test.com"
                      :score 98})

(def test-document-3 {:name  "Robert Jones"
                      :email "robert.jones@example.com"
                      :score 80})

(def test-document-4 {:name  "Sarah Parker"
                      :email "sarah.parker@example.com"
                      :score 59})

(declare ^{:dynamic true} *clj-view-svr-config*
         ^{:dynamic true} *test-database*)

; don't squash existing canonical "clojure" view server config
(def ^{:private true} view-server-name :clutch-test)

(.addMethod view-transformer
            view-server-name
            (get-method view-transformer :clojure))

(use-fixtures
  :once
  #(binding [*clj-view-svr-config* (try
                                     (configure-view-server (utils/url "") (view-server/view-server-exec-string) :language view-server-name)
                                     (catch java.io.IOException e (.printStackTrace e)))]
     (when-not *clj-view-svr-config*
       (println "Could not autoconfigure clutch view server,"
                "skipping tests that depend upon it!"
                (view-server/view-server-exec-string)))
     (%)))

(defn test-database-name
  [test-name]
  (str "test-db-" (str/re-sub #"[^$]+\$([^@]+)@.*" "$1" (str test-name))))

(defmacro defdbtest [name & body]
  `(deftest ~name
     (binding [*test-database* (get-database (utils/url (test-database-name ~name)))]
       (try
        (with-db *test-database* ~@body)
        (finally
         (delete-database *test-database*))))))

(deftest check-couchdb-connection
  (is (= "Welcome" (:couchdb (couchdb-info (utils/url "foo"))))))

(deftest get-list-check-and-delete-database
  (let [name "clutch_test_db"
        url (utils/url name)
        *test-database* (get-database url)]
    (is (= name (:db_name *test-database*)))
    (is ((set (all-databases url)) name))
    (is (= name (:db_name (database-info url))))
    (is (:ok (delete-database url)))
    (is (nil? ((set (all-databases url)) name)))))
 
(deftest database-name-escaping
  (let [name (test-database-name "foo_$()+-/bar")]
    (try
      (let [dbinfo (get-database name)]
        (is (= name (:db_name dbinfo))))
      (put-document name {:_id "a" :b 0})
      (is (= 0 (:b (get-document name "a"))))
      (delete-document name (get-document name "a"))
      (is (nil? (get-document name "a")))
      (finally
        (delete-database name)))))

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
      (is (= id (:_id (put-document {} :id id :request-params {:batch "ok"}))) id-desc)
      (catch Exception e
        (is false (str "Error for " id-desc ": " (.getMessage e)))))))

(defdbtest create-a-document
  (let [document (put-document test-document-1)]
    (are [k] (contains? document k)
         :_id :_rev)))

(defdbtest create-a-document-with-id
  (let [document (put-document test-document-2 :id "my_id")]
    (is (= "my_id" (document :_id)))))

(defrecord Foo [a])

(defdbtest create-with-record
  (let [rec (put-document (Foo. "bar") :id "docid")]
    (is (instance? Foo rec)))
  (is (= "bar" (-> "docid" get-document :a))))

(defdbtest get-a-document
  (let [created-document (put-document test-document-3)
        fetched-document (get-document (created-document :_id))]
    (are [x y z] (= x y z)
         "Robert Jones" (created-document :name) (fetched-document :name)
         "robert.jones@example.com" (created-document :email) (fetched-document :email)
         80 (created-document :score) (fetched-document :score))))

(defdbtest get-a-document-revision
  (let [created-document (put-document test-document-3)
        updated-doc (update-document (assoc created-document :newentry 1))
        fetched-document (get-document (:_id created-document)
                                       :rev (:_rev created-document))]
    (are [x y z] (= x y z)
         "Robert Jones" (created-document :name) (fetched-document :name)
         "robert.jones@example.com" (created-document :email) (fetched-document :email)
         80 (:score created-document) (:score fetched-document)
         nil (:newentry created-document) (:newentry fetched-document))
    (is (= 1 (:newentry updated-doc)))))

(defdbtest verify-response-code-access
  (put-document test-document-1 :id "some_id")
  (binding [http-client/*response-code* nil]
    (is (thrown? java.io.IOException (put-document test-document-1 :id "some_id")))
    (is (== 409 http-client/*response-code*))))

(defdbtest update-a-document
  (let [id (:_id (put-document test-document-4))]
    (update-document (get-document id) {:email "test@example.com"})
    (is (= "test@example.com" (:email (get-document id)))))
  (testing "no update map or fn"
    (let [id (:_id (put-document test-document-4))]
      (update-document (merge (get-document id) {:email "test@example.com"}))
      (is (= "test@example.com" (:email (get-document id)))))))

(defdbtest update-a-document-with-a-function
  (let [id (:_id (put-document test-document-3))]
    (update-document (get-document id) update-in [:score] + 3)
    (is (= 83 (:score (get-document id))))))

(defdbtest update-with-updated-map
  (-> test-document-3
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
  (put-document test-document-2 :id "my_id")
  (is (get-document "my_id"))
  (is (true? (:ok (delete-document (get-document "my_id")))))
  (is (nil? (get-document "my_id"))))

(defdbtest copy-a-document
  (let [doc (put-document test-document-1 :id "src")]
    (copy-document "src" "dst")
    (copy-document doc "dst2")
    (is (= (dissoc-meta doc)
           (-> "dst" get-document dissoc-meta)
           (-> "dst2" get-document dissoc-meta)))))

(defdbtest copy-document-overwrite
  (let [doc (put-document test-document-1 :id "src")
        overwrite (put-document test-document-2 :id "overwrite")]
    (copy-document doc overwrite)
    (is (= (dissoc-meta doc) (dissoc-meta (get-document "overwrite"))))))

(defdbtest copy-document-attachments
  (let [doc (put-document test-document-1 :id "src")
        file (File. (str resources-path "/couchdb.png"))
        doc (put-attachment doc file :filename :image)
        doc (-> doc :id get-document)]
    (copy-document "src" "dest")
    (let [copy (get-document "dest")
          copied-attachment (get-attachment copy :image)]
      (is (= (dissoc-meta doc) (dissoc-meta copy)))
      (is (= (-> file io/to-byte-array seq) (-> copied-attachment io/to-byte-array seq))))))

(defdbtest copy-document-fail-overwrite
  (put-document test-document-1 :id "src")
  (put-document test-document-2 :id "overwrite")
  (binding [http-client/*response-code* nil]
    (is (thrown? java.io.IOException (copy-document "src" "overwrite")))
    (is (== 409 http-client/*response-code*))))

(defdbtest get-all-documents-with-query-parameters
  (put-document test-document-1 :id "a")
  (put-document test-document-2 :id "b")
  (put-document test-document-3 :id "c")
  (let [all-documents-descending (all-documents {:include_docs true :descending true})
        all-documents-ascending  (all-documents {:include_docs true :descending false})]
    (are [results] (= 3 (:total_rows (meta results)))
         all-documents-descending
         all-documents-ascending)
    (are [name] (= "Robert Jones" name)
         (-> all-documents-descending first :doc :name)
         (-> all-documents-ascending last :doc :name))))

(defdbtest get-all-documents-with-post-keys
  (put-document test-document-1 :id "1")
  (put-document test-document-2 :id "2")
  (put-document test-document-3 :id "3")
  (put-document test-document-3 :id "4")
  (let [all-documents               (all-documents {:include_docs true} {:keys ["1" "2"]})
        all-documents-matching-keys all-documents]
    (is (= ["John Smith" "Jane Thompson"]
           (map #(-> % :doc :name) all-documents-matching-keys)))
    (is (= 4 (:total_rows (meta all-documents))))))

(defdbtest create-a-design-view
  (when *clj-view-svr-config*
    (let [view-document (save-view "users" 
                                   (view-server-fns view-server-name
                                     {:names-with-score-over-70
                                      {:map #(if (> (:score %) 70) [[nil (:name %)]])}}))]
      (is (map? (-> (get-document (view-document :_id)) :views :names-with-score-over-70))))))

(defdbtest use-a-design-view-with-spaces-in-key
  (when *clj-view-svr-config*
    (put-document test-document-1)
    (put-document test-document-2)
    (put-document test-document-3)
    (put-document test-document-4)
    (save-view "users"
               (view-server-fns view-server-name
                                {:names-and-scores
                                 {:map (fn [doc] [[(:name doc) (:score doc)]])}}))
    (is (= [98]
             (map :value (get-view "users" :names-and-scores {:key "Jane Thompson"}))))))

(defdbtest use-a-design-view-with-map-only
  (when *clj-view-svr-config*
    (put-document test-document-1)
    (put-document test-document-2)
    (put-document test-document-3)
    (put-document test-document-4)
    (save-view "users"
      (view-server-fns view-server-name
        {:names-with-score-over-70-sorted-by-score 
         {:map #(if (> (:score %) 70) [[(:score %) (:name %)]])}}))
    (is (= ["Robert Jones" "Jane Thompson"]
          (map :value (get-view "users" :names-with-score-over-70-sorted-by-score))))
    (put-document {:name "Test User 1" :score 55})
    (put-document {:name "Test User 2" :score 78})
    (is (= ["Test User 2" "Robert Jones" "Jane Thompson"]
          (map :value (get-view "users" :names-with-score-over-70-sorted-by-score))))
    (save-view "users"
      (view-server-fns view-server-name
        {:names-with-score-less-than-70-sorted-by-name
         {:map #(if (< (:score %) 70) [[(:name %) (:name %)]])}}))
    (is (= ["John Smith" "Sarah Parker" "Test User 1"]
          (map :value (get-view "users" :names-with-score-less-than-70-sorted-by-name))))))

(defdbtest use-a-design-view-with-post-keys
  (when *clj-view-svr-config*
    (put-document test-document-1)
    (put-document test-document-2)
    (put-document test-document-3)
    (put-document test-document-4)
    (put-document {:name "Test User 1" :score 18})
    (put-document {:name "Test User 2" :score 7})
    (save-view "users"
      (view-server-fns view-server-name
        {:names-keyed-by-scores
         {:map #(cond (< (:score %) 30) [[:low (:name %)]]
                      (< (:score %) 70) [[:medium (:name %)]]
                      :else [[:high (:name %)]])}}))
    (is (= #{"Sarah Parker" "John Smith" "Test User 1" "Test User 2"}
           (->> (get-view "users" :names-keyed-by-scores {} {:keys [:medium :low]})
             (map :value)
             set)))))

(defdbtest use-a-design-view-with-both-map-and-reduce
  (when *clj-view-svr-config*
    (put-document test-document-1)
    (put-document test-document-2)
    (put-document test-document-3)
    (put-document test-document-4)
    (save-view "scores"
      (view-server-fns view-server-name
        {:sum-of-all-scores
         {:map    (fn [doc] [[nil (:score doc)]])
          :reduce (fn [keys values _] (apply + values))}}))
    (is (= 302 (-> (get-view "scores" :sum-of-all-scores) first :value)))
    (put-document {:score 55})
    (is (= 357 (-> (get-view "scores" :sum-of-all-scores) first :value)))))

(defdbtest use-a-design-view-with-multiple-emits
  (when *clj-view-svr-config*
    (put-document {:players ["Test User 1" "Test User 2" "Test User 3"]})
    (put-document {:players ["Test User 4"]})
    (put-document {:players []})
    (put-document {:players ["Test User 5" "Test User 6" "Test User 7" "Test User 8"]})
    (save-view "count"
               (view-server-fns view-server-name
                 {:number-of-players
                  {:map (fn [doc] (map (fn [d] [d 1]) (:players doc)))
                   :reduce (fn [keys values _] (reduce + values))}}))
    (is (= 8 (-> (get-view "count" :number-of-players) first :value)))))

(defdbtest use-ad-hoc-view
  (when *clj-view-svr-config*
    (put-document test-document-1)
    (put-document test-document-2)
    (put-document test-document-3)
    (put-document test-document-4)
    (let [view (ad-hoc-view
                 (view-server-fns view-server-name
                   {:map (fn [doc] (if (re-find #"example\.com$" (:email doc))
                                   [[nil (:email doc)]]))}))]
      (is (= #{"robert.jones@example.com" "sarah.parker@example.com"}
            (set (map :value view)))))))

(defdbtest use-ad-hoc-view-with-javascript-view-server
  (put-document test-document-1)
  (put-document test-document-2)
  (put-document test-document-3)
  (put-document test-document-4)
  (let [view (ad-hoc-view
               (view-server-fns :javascript
                 {:map      "function(doc){if(doc.email.indexOf('test.com')>0)emit(null,doc.email);}"}))]
    (is (= #{"john.smith@test.com" "jane.thompson@test.com"}
           (set (map :value view))))))

(defdbtest bulk-update-new-documents
  (bulk-update [test-document-1
                test-document-2
                test-document-3
                test-document-4])
  (is (= 4 (:total_rows (meta (all-documents))))))

(defdbtest bulk-update-documents
  (bulk-update [test-document-1
                test-document-2
                test-document-3
                test-document-4])
  (bulk-update (->> (all-documents {:include_docs true})
                 (map :doc)
                 (map #(assoc % :updated true))))
  (is (every? true? (map #(-> % :doc :updated) (all-documents {:include_docs true})))))

(defdbtest lazy-view-results
  (bulk-update (map (comp (partial hash-map :_id) str) (range 50000)))
  (is (= {:total_rows 50000 :offset 0} (meta (all-documents))))
  (let [time #(let [t (System/currentTimeMillis)]
                [(%) (- (System/currentTimeMillis) t)])
        [f tf] (time #(first (all-documents)))
        [l tl] (time #(last (all-documents)))]
    ; any other way to check that the returned seq is properly lazy?
    (is (< 10 (/ tl tf)))))

(defdbtest inline-attachments
  (let [clojure-img-file (str resources-path "/clojure.png")
        couchdb-img-file (str resources-path "/couchdb.png")
        couch-filename (keyword "couchdb 2.png")
        created-document (put-document test-document-4
                           :attachments [clojure-img-file
                                         {:data (FileInputStream. couchdb-img-file)
                                          :filename couch-filename :mime-type "image/png"}])
        fetched-document (get-document (created-document :_id))]
    (are [attachment-keys] (= #{:clojure.png couch-filename} attachment-keys) 
         (set (keys (created-document :_attachments)))
         (set (keys (fetched-document :_attachments))))
    (are [doc file-key] (= "image/png" (-> doc :_attachments file-key :content_type))
         created-document :clojure.png
         fetched-document :clojure.png
         created-document couch-filename
         fetched-document couch-filename)
    (are [path file-key] (= (.length (File. path)) (-> fetched-document :_attachments file-key :length))
         clojure-img-file :clojure.png
         couchdb-img-file couch-filename)))

(defdbtest standalone-attachments
  (let [document (put-document test-document-1)
        path (str resources-path "/couchdb.png")
        filename-with-space (keyword "couchdb - image2")
        updated-document-meta (put-attachment document path :filename :couchdb-image)
        updated-document-meta (put-attachment (assoc document :_rev (:rev updated-document-meta))
                                (FileInputStream. path) :filename filename-with-space :mime-type "image/png")
        document-with-attachments (get-document (updated-document-meta :id) :attachments true)]
    (is (= #{:couchdb-image filename-with-space} (set (keys (:_attachments document-with-attachments)))))
    (is (= "image/png" (-> document-with-attachments :_attachments :couchdb-image :content_type)))
    (is (contains? (-> document-with-attachments :_attachments :couchdb-image) :data))
    
    (is (= (-> document-with-attachments :_attachments :couchdb-image (select-keys [:data :content_type :length]))
           (-> document-with-attachments :_attachments filename-with-space (select-keys [:data :content_type :length]))))
    
    (is (thrown? IllegalArgumentException (put-attachment document (Object.))))
    (is (thrown? IllegalArgumentException (put-attachment document (ByteArrayInputStream. (make-array Byte/TYPE 0)))))))

(defdbtest stream-attachments
  (let [document                  (put-document test-document-4)
        updated-document-meta     (put-attachment document (str resources-path "/couchdb.png")
                                    :filename :couchdb-image
                                    :mime-type "other/mimetype")
        document-with-attachments (get-document (updated-document-meta :id) :attachments true)
        data (io/to-byte-array (java.io.File. (str resources-path "/couchdb.png")))]
      (is (= "other/mimetype" (-> document-with-attachments :_attachments :couchdb-image :content_type)))
      (is (= (seq data) (-> (get-attachment document-with-attachments :couchdb-image) io/to-byte-array seq)))))

(deftest replicate-a-database
  (try
    (let [source-database (get-database "source_test_db")
          target-database (get-database "target_test_db")]
      (with-db source-database
        (bulk-update [test-document-1
                      test-document-2
                      test-document-3
                      test-document-4]))
      (replicate-database source-database target-database)
      (with-db target-database
        (is (= 4 (:total_rows (meta (all-documents)))))))
    (finally
      (delete-database "source_test_db")
      (delete-database "target_test_db"))))

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

(defdbtest watch-for-change
  (watch-changes :check-id (partial check-id-changes-test "Watch database"))
  (put-document test-document-2 :id "target-id"))

(defdbtest ensure-stop-changes
  (watch-changes :foo println)
  (letfn [(tracking-changes? [] (-> @@#'com.ashafa.clutch/watched-databases
                                  (get (str *test-database*))
                                  :foo))]
    (is (tracking-changes?))
    (stop-changes :foo)
    (is (not (tracking-changes?)))))

(defdbtest multiple-watchers-for-change
  (watch-changes :check-id (partial check-id-changes-test "Multiple watchers - id"))
  (watch-changes :check-seq (partial check-seq-changes-test "Multiple watchers - seq"))
  (is (= #{:check-id :check-seq} (set (:watchers (database-info)))))
  (put-document test-document-2 :id "target-id"))

(defdbtest multiple-changes
  (watch-changes :check-delete (partial check-delete-changes-test "Multiple changes"))
  (let [document-1 (put-document test-document-1 :id "not-target-id")
        document-2 (put-document test-document-2 :id "target-id")
        document-3 (put-document test-document-3 :id "another-random-id")]
    (update-document document-1 {:score 0})
    (delete-document document-2)))

(defdbtest changes-filter
  (save-filter "scores"
               (view-server-fns view-server-name
                 {:less-than-50 (fn [document request] (if (< (:score document) 50) true false))}))
  (watch-changes :check-id (partial check-id-changes-test "Filter")
                 :filter "scores/less-than-50")
  (put-document {:name "tester 1" :score 22} :id "target-id")
  (put-document {:name "tester 2" :score 79} :id "not-target-id"))

(defdbtest changes-filter-with-query-params
  (save-filter "scores"
               (view-server-fns view-server-name
                 {:more-than-50-from-a-user (fn [document request]
                                                     (if (and (> (:score document) 50)
                                                              (= (:name document) (-> request :query :name)))
                                                       true false))}))
  (watch-changes :check-id (partial check-id-changes-test "Filter with query parameters") 
                 :filter "scores/more-than-50-from-a-user" :name "tester 1")
  (put-document {:name "tester 1" :score 51} :id "target-id")
  (put-document {:name "tester 2" :score 48} :id "not-target-id"))

(deftest direct-db-config-usage
  (let [db "direct-db-config-usage"]
    (try
      (create-database db)
      (let [doc (put-document db test-document-1 :id "foo")]
        (update-document db doc {:a 5})
        (is (= (assoc test-document-1 :a 5) (dissoc-meta (get-document db "foo")))))
      (finally
        (delete-database "direct-db-config-usage")))))

(deftest multiple-binding-levels
  (let [db1 "multiple-binding-levels"
        db2 (str db1 2)]
    (with-db db1
      (try
        (is (= db1 (:db_name (get-database))))
        (put-document {} :id "1")
        (with-db db2
          (try
            (is (= db2 (:db_name (get-database))))
            (is (nil? (get-document "1")))
            (let [doc (put-document {} :id "2")]
              (update-document doc {:a 5})
              (is (= {:a 5} (dissoc-meta (get-document "2")))))
            (finally
              (delete-database))))
        (is (nil? (get-document "2")))
        (finally
          (delete-database))))))