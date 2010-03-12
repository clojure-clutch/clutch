(ns #^{:author "Tunde Ashafa"}
  test-clutch
  (:require [com.ashafa.clutch.http-client :as http-client]
            [clojure.contrib.duck-streams :as duck-streams])
  (:use com.ashafa.clutch 
        clojure.test)
  (:import java.io.ByteArrayInputStream))

(set-clutch-defaults! {:language "clojure"})

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

; we should be able to push a clojure query_server cmd to the localhost couch
; without a problem (maybe using a test-only :language to avoid clobbering a "real" view svr?),
; but that's for another day
(declare *clj-view-svr-config* test-database)

(use-fixtures
  :once
  #(binding [*clj-view-svr-config* (http-client/couchdb-request @*defaults* :get "_config/query_servers/clojure")]
     (when-not *clj-view-svr-config*
       (println "Clojure view server not available, skipping tests that depend upon it!"))
     (%)))

(defmacro defdbtest [name & body]
  `(deftest ~name
     (binding [test-database (get-database {:name "clutch_test_db"})]
       (try
        (with-db test-database ~@body)
        (finally
         (delete-database test-database))))))

(deftest check-couchdb-connection
  (is (= "Welcome" (:couchdb (couchdb-info)))))

(deftest get-list-check-and-delete-database
  (let [test-database (get-database {:name "clutch_test_db"})]
    (is (:ok test-database))
    (is ((set (all-databases)) (:name test-database)))
    (is (= (:name test-database) (:db_name (database-info test-database))))
    (is (:ok (delete-database test-database)))))

(defdbtest create-a-document
  (let [document (create-document test-document-1)]
    (are [k] (contains? document k)
         :_id :_rev)))

(defdbtest create-a-document-with-id
  (let [document (create-document test-document-2 "my_id")]
    (is (= "my_id" (document :_id)))))

(defdbtest get-a-document
  (let [created-document (create-document test-document-3)
        fetched-document (get-document (created-document :_id))]
    (are [x y z] (= x y z)
         "Robert Jones" (created-document :name) (fetched-document :name)
         "robert.jones@example.com" (created-document :email) (fetched-document :email)
         80 (created-document :score) (fetched-document :score))))

(defdbtest verify-response-code-access
  (create-document test-document-1 "some_id")
  (binding [http-client/*response-code* (atom nil)]
    (is (thrown? java.io.IOException (create-document test-document-1 "some_id")))
    (is (== 409 @http-client/*response-code*))))

(defdbtest update-a-document
  (let [id (:_id (create-document test-document-4))]
    (update-document (get-document id) {:email "test@example.com"})
    (is (= "test@example.com" (:email (get-document id))))))

(defdbtest delete-a-document
  (create-document test-document-2 "my_id")
  (is (get-document "my_id"))
  (is (true? (:ok (delete-document (get-document "my_id")))))
  (is (nil? (get-document "my_id"))))

(defdbtest get-all-documents-with-query-parameters
  (create-document test-document-1 "a")
  (create-document test-document-2 "b")
  (create-document test-document-3 "c")
  (let [all-documents-descending (get-all-documents-meta {:include_docs true :descending true})
        all-documents-ascending  (get-all-documents-meta {:include_docs true :descending false})]
    (are [total_rows] (= 3 total_rows)
         (:total_rows all-documents-descending)
         (:total_rows all-documents-ascending))
    (are [name] (= "Robert Jones" name)
         (-> all-documents-descending :rows first :doc :name)
         (-> all-documents-ascending :rows last :doc :name))))

(defdbtest get-all-documents-with-post-keys
  (create-document test-document-1 "1")
  (create-document test-document-2 "2")
  (create-document test-document-3 "3")
  (create-document test-document-3 "4")
  (let [all-documents               (get-all-documents-meta {:include_docs true} {:keys ["1" "2"]})
        all-documents-matching-keys (:rows all-documents)]
    (is (= ["John Smith" "Jane Thompson"]
           (map #(-> % :doc :name) all-documents-matching-keys)))
    (is (= 4 (:total_rows all-documents)))))

(defdbtest create-a-design-view
  (when *clj-view-svr-config*
    (let [view-document (save-view "users" :names-with-score-over-70
                                     (with-clj-view-server
                                       {:map #(if (> (:score %) 70) [[nil (:name %)]])}))]
      (is (map? (-> (get-document (view-document :_id)) :views :names-with-score-over-70))))))

(defdbtest use-a-design-view-with-spaces-in-key
  (when *clj-view-svr-config*
    (create-document test-document-1)
    (create-document test-document-2)
    (create-document test-document-3)
    (create-document test-document-4)
    (save-view "users" :names-and-scores
		 (with-clj-view-server
		  {:map (fn [doc] [[(:name doc) (:score doc)]])}))
    (is (= [98]
	   (map :value (:rows (get-view "users" :names-and-scores {:key "Jane Thompson"})))))))

(defdbtest use-a-design-view-with-map-only
  (when *clj-view-svr-config*
    (create-document test-document-1)
    (create-document test-document-2)
    (create-document test-document-3)
    (create-document test-document-4)
    (save-view "users" :names-with-score-over-70-sorted-by-score
      (with-clj-view-server
        {:map #(if (> (:score %) 70) [[(:score %) (:name %)]])}))
    (is (= ["Robert Jones" "Jane Thompson"]
          (map :value (:rows (get-view "users" :names-with-score-over-70-sorted-by-score)))))
    (create-document {:name "Test User 1" :score 55})
    (create-document {:name "Test User 2" :score 78})
    (is (= ["Test User 2" "Robert Jones" "Jane Thompson"]
          (map :value (:rows (get-view "users" :names-with-score-over-70-sorted-by-score)))))
    (save-view "users" :names-with-score-less-than-70-sorted-by-name
      (with-clj-view-server
        {:map #(if (< (:score %) 70) [[(:name %) (:name %)]])}))
    (is (= ["John Smith" "Sarah Parker" "Test User 1"]
          (map :value (:rows (get-view "users" :names-with-score-less-than-70-sorted-by-name)))))))

(defdbtest use-a-design-view-with-post-keys
  (when *clj-view-svr-config*
    (create-document test-document-1)
    (create-document test-document-2)
    (create-document test-document-3)
    (create-document test-document-4)
    (create-document {:name "Test User 1" :score 18})
    (create-document {:name "Test User 2" :score 7})
    (save-view "users" :names-keyed-by-scores
      (with-clj-view-server
        {:map #(cond (< (:score %) 30) [[:low (:name %)]]
                     (< (:score %) 70) [[:medium (:name %)]]
                     :else [[:high (:name %)]])}))
    (is (= #{"Sarah Parker" "John Smith" "Test User 1" "Test User 2"}
          (set (map :value (:rows (get-view "users" :names-keyed-by-scores {} {:keys [:medium :low]}))))))))
    
(defdbtest use-a-design-view-with-both-map-and-reduce
  (when *clj-view-svr-config*
    (create-document test-document-1)
    (create-document test-document-2)
    (create-document test-document-3)
    (create-document test-document-4)
    (save-view "scores" :sum-of-all-scores
      (with-clj-view-server
        {:map    (fn [doc] [[nil (:score doc)]])
         :reduce (fn [keys values _] (apply + values))}))
    (is (= 302 (-> (get-view "scores" :sum-of-all-scores) :rows first :value)))
    (create-document {:score 55})
    (is (= 357 (-> (get-view "scores" :sum-of-all-scores) :rows first :value)))))

(defdbtest use-ad-hoc-view
  (when *clj-view-svr-config*
    (create-document test-document-1)
    (create-document test-document-2)
    (create-document test-document-3)
    (create-document test-document-4)
    (let [view (ad-hoc-view
                 (with-clj-view-server
                   {:map (fn [doc] (if (re-find #"example\.com$" (:email doc))
                                     [[nil (:email doc)]]))}))]
      (is (= #{"robert.jones@example.com" "sarah.parker@example.com"}
            (set (map :value (:rows view))))))))

(defdbtest use-ad-hoc-view-with-javascript-view-server
  (create-document test-document-1)
  (create-document test-document-2)
  (create-document test-document-3)
  (create-document test-document-4)
  (let [view (ad-hoc-view
              {:language "javascript"
               :map      "function(doc){if(doc.email.indexOf('test.com')>0)emit(null,doc.email);}"})]
    (is (= #{"john.smith@test.com" "jane.thompson@test.com"}
           (set (map :value (:rows view)))))))

(defdbtest bulk-update-new-documents
  (bulk-update [test-document-1
                test-document-2
                test-document-3
                test-document-4])
  (is (= 4 (:total_rows (get-all-documents-meta)))))

(defdbtest bulk-update-documents
  (bulk-update [test-document-1
                test-document-2
                test-document-3
                test-document-4])
  (bulk-update (map :doc (:rows (get-all-documents-meta {:include_docs true}))) {:updated true})
  (is (every? true? (map #(-> % :doc :updated) (:rows (get-all-documents-meta {:include_docs true}))))))

(defdbtest inline-attachments
  (let [resources-path   (or (.getParent (java.io.File. *file*)) "src/test/clojure")
        clojure-img-file (str resources-path "/clojure.png")
        couchdb-img-file (str resources-path "/couchdb.png")
        created-document (create-document test-document-4 [clojure-img-file couchdb-img-file])
        fetched-document (get-document (created-document :_id))]
    (are [attachment-keys] (= #{:clojure.png :couchdb.png} attachment-keys) 
         (set (keys (created-document :_attachments)))
         (set (keys (fetched-document :_attachments))))
    (are [content-type] (= "image/png" content-type)
         (-> created-document :_attachments :clojure.png :content_type)
         (-> created-document :_attachments :couchdb.png :content_type)
         (-> fetched-document :_attachments :clojure.png :content_type)
         (-> fetched-document :_attachments :couchdb.png :content_type))
    (are [file-length document-attachment-length] (= file-length document-attachment-length)
         (.length (java.io.File. clojure-img-file)) (-> created-document :_attachments :clojure.png :length)
         (.length (java.io.File. couchdb-img-file)) (-> created-document :_attachments :couchdb.png :length)
         (.length (java.io.File. clojure-img-file)) (-> fetched-document :_attachments :clojure.png :length)
         (.length (java.io.File. couchdb-img-file)) (-> fetched-document :_attachments :couchdb.png :length))))

(defdbtest standalone-attachments
  (let [resources-path            (or (.getParent (java.io.File. *file*)) "src/test/clojure")
        document                  (create-document test-document-1)
        updated-document-meta     (update-attachment document (str resources-path "/couchdb.png") :couchdb-image)
        document-with-attachments (get-document (updated-document-meta :id) {:attachments true})]
    (is (= [:couchdb-image] (keys (document-with-attachments :_attachments))))
    (is (= "image/png" (-> document-with-attachments :_attachments :couchdb-image :content_type)))
    (is (contains? (-> document-with-attachments :_attachments :couchdb-image) :data))
    (is (thrown? IllegalArgumentException (update-attachment document (Object.))))
    (is (thrown? IllegalArgumentException (update-attachment document (ByteArrayInputStream. (make-array Byte/TYPE 0)))))))

(defdbtest stream-attachments
  (let [resources-path            (or (.getParent (java.io.File. *file*)) "src/test/clojure")
        document                  (create-document test-document-4)
        updated-document-meta     (update-attachment document (str resources-path "/couchdb.png") :couchdb-image "other/mimetype")
        document-with-attachments (get-document (updated-document-meta :id) {:attachments true})
        data (duck-streams/to-byte-array (java.io.File. (str resources-path "/couchdb.png")))]
      (is (= "other/mimetype" (-> document-with-attachments :_attachments :couchdb-image :content_type)))
      (is (= (seq data) (-> (get-attachment document-with-attachments :couchdb-image) duck-streams/to-byte-array seq)))))

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
       (is (= 4 (:total_rows (get-all-documents-meta))))))
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
     (is (= (:id change-meta) "some-id")))))

(defn check-seq-changes-test
  [description change-meta]
  (if-not (:last_seq change-meta)
    (report-change description
     (is (= (:seq change-meta) 1)))))

(defn check-delete-changes-test
  [description change-meta]
  (if (:deleted change-meta)
    (report-change description
     (is (= (:id change-meta) "some-other-id"))
     (is (= (:seq change-meta) 5)))))

(defdbtest watch-for-change
  (watch-changes test-database :check-id (partial check-id-changes-test "Watch database"))
  (create-document test-document-2 "some-id"))

(defdbtest multiple-watchers-for-change
  (watch-changes test-database :check-id (partial check-id-changes-test "Multiple watchers - id"))
  (watch-changes test-database :check-seq (partial check-seq-changes-test "Multiple watchers - seq"))
  (is (= #{:check-id :check-seq} (set (:watchers (database-info test-database)))))
  (create-document test-document-2 "some-id"))

(defdbtest multiple-changes
  (watch-changes test-database :check-delete (partial check-delete-changes-test "Multiple changes"))
  (let [document-1 (create-document test-document-1 "some-id")
        document-2 (create-document test-document-2 "some-other-id")
        document-3 (create-document test-document-3 "another-id")]
    (update-document document-1 {:score 0})
    (delete-document document-2)))

(defdbtest changes-filters
  (save-filter "scores"
               (with-clj-view-server
                 {:less-than-50 (fn [document request] (if (< (:score document) 50) true false))}))
  (watch-changes test-database :check-id (partial check-id-changes-test "Filter")
                 {:filter "scores/less-than-50"})
  (create-document {:name "tester 1" :score 22} "some-id")
  (create-document {:name "tester 2" :score 79} "some-other-id"))

(defdbtest changes-filters-with-query-params
  (save-filter "scores"
               (with-clj-view-server
                 {:more-than-50-from-a-user (fn [document request]
                                                     (if (and (< (:score document) 50)
                                                              (= (:name document) (-> request :query :name)))
                                                       true false))}))
  (watch-changes test-database :check-id (partial check-id-changes-test "Filter with query parameters") 
                 {:filter "scores/more-than-50-from-a-user" :name "tester 1"})
  (create-document {:name "tester 1" :score 22} "some-id")
  (create-document {:name "tester 2" :score 79} "some-other-id"))