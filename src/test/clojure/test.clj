(ns clutch
 (:require [com.ashafa.clutch.http-client :as http-client])
 (:use com.ashafa.clutch
   (clojure.contrib [test-is :as test-is])))

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
(declare *clj-view-svr-config*)

(use-fixtures
  :once
  #(binding [*clj-view-svr-config* (http-client/couchdb-request *defaults* :get "_config/query_servers/clojure")]
     (when-not *clj-view-svr-config*
       (println "Clojure view server not available, skipping tests that depend upon it!"))
     (%)))

(defmacro defdbtest [name & body]
  `(deftest ~name
     (let [test-database# (create-database {:name "clutch_test_db"})]
       (try
        (with-db test-database# ~@body)
        (finally
         (delete-database test-database#))))))

(deftest check-couchdb-connection
  (is (= "Welcome" (:couchdb (couchdb-info)))))

(deftest create-list-check-and-delete-database
  (let [test-database (create-database {:name "clutch_test_db"})]
    (is (:ok test-database))
    (is ((set (all-couchdb-databases)) (:name test-database)))
    (is (= (:name test-database) (:db_name (database-info test-database))))
    (is (:ok (delete-database test-database)))))

(defdbtest create-a-document
  (let [document-meta (create-document test-document-1)]
    (are (contains? document-meta _)
         :ok :id :rev)))

(defdbtest create-a-document-with-id
  (let [document-meta (create-document test-document-2 "my_id")]
    (is (= "my_id" (document-meta :id)))))

(defdbtest get-a-document
  (let [document-meta (create-document test-document-3)
        document      (get-document (document-meta :id))]
    (are (= _1 _2)
         "Robert Jones" (document :name)
         "robert.jones@example.com" (document :email)
         80 (document :score))))

(defdbtest verify-response-code-access
  (create-document test-document-1 "some_id")
  (binding [http-client/*response-code* (atom nil)]
    (is (thrown? java.io.IOException (create-document test-document-1 "some_id")))
    (is (== 409 @http-client/*response-code*))))

(defdbtest update-a-document
  (let [id (:id (create-document test-document-4))]
    (update-document (get-document id) {:email "test@example.com"})
    (is (= "test@example.com" (:email (get-document id))))))

(defdbtest update-a-document-with-a-function
  (let [id (:id (create-document {:score 60}))]
    (update-document (get-document id) (partial + 4) [:score])
    (is (= 64 (:score (get-document id))))))

(defdbtest delete-a-document
  (create-document test-document-2 "my_id")
  (is (get-document "my_id"))
  (is (true? (:ok (delete-document (get-document "my_id")))))
  (is (nil? (get-document "my_id"))))

(defdbtest get-all-documents-with-query-parameters
  (let [document-1               (create-document test-document-1 1)
        document-2               (create-document test-document-2 2)
        document-3               (create-document test-document-3 3)
        all-documents-descending (get-all-documents {:include_docs true :descending true})
        all-documents-ascending  (get-all-documents {:include_docs true :descending false})]
    (are (= 3 _1)
         (:total_rows all-documents-descending)
         (:total_rows all-documents-ascending))
    (are (= "Robert Jones" _1)
         (-> all-documents-descending :rows first :doc :name)
         (-> all-documents-ascending :rows last :doc :name))))

(defdbtest get-all-documents-with-post-keys
  (let [document-1                  (create-document test-document-1 1)
        document-2                  (create-document test-document-2 2)
        document-3                  (create-document test-document-3 3)
        all-documents               (get-all-documents {:include_docs true} {:keys ["1" :2]}) ;; in _all_docs, keys = document id as string or symbol 
        all-documents-matching-keys (:rows all-documents)]
    (is (= ["John Smith" "Jane Thompson"]
           (map #(-> % :doc :name) all-documents-matching-keys)))
    (is (= 3 (:total_rows all-documents)))))

(defdbtest create-a-design-view
  (when *clj-view-svr-config*
    (let [document-meta (create-view "users" :names-with-score-over-70
                          (with-clj-view-server
                            #(if (> (:score %) 70) [nil (:name %)])))]
      (is (map? (-> (get-document (document-meta :id)) :views :names-with-score-over-70))))))

(defdbtest use-a-design-view-with-spaces-in-key
  (when *clj-view-svr-config*
    (create-document test-document-1)
    (create-document test-document-2)
    (create-document test-document-3)
    (create-document test-document-4)
    (create-view "users" :names-and-scores
		 (with-clj-view-server
		  (fn [doc] [(:name doc) (:score doc)])))
    (is (= [98]
	   (map :value (:rows (get-view "users" :names-and-scores {:key "Jane Thompson"})))))))

(defdbtest use-a-design-view-with-map-only
  (when *clj-view-svr-config*
    (create-document test-document-1)
    (create-document test-document-2)
    (create-document test-document-3)
    (create-document test-document-4)
    (create-view "users" :names-with-score-over-70-sorted-by-score
      (with-clj-view-server
        #(if (> (:score %) 70) [(:score %) (:name %)])))
    (is (= ["Robert Jones" "Jane Thompson"]
          (map :value (:rows (get-view "users" :names-with-score-over-70-sorted-by-score)))))
    (create-document {:name "Test User 1" :score 55})
    (create-document {:name "Test User 2" :score 78})
    (is (= ["Test User 2" "Robert Jones" "Jane Thompson"]
          (map :value (:rows (get-view "users" :names-with-score-over-70-sorted-by-score)))))))

(defdbtest use-a-design-view-with-post-keys
  (when *clj-view-svr-config*
    (create-document test-document-1)
    (create-document test-document-2)
    (create-document test-document-3)
    (create-document test-document-4)
    ;; lets add some low score users...
    (create-document {:name "Test User 1" :score 18})
    (create-document {:name "Test User 2" :score 7})
    (create-view "users" :names-keyed-by-scores
      (with-clj-view-server
        #(cond (< (:score %) 30) [:low (:name %)]
               (< (:score %) 70) [:medium (:name %)]
               :else [:high (:name %)])))
    (is (= #{"Sarah Parker" "John Smith" "Test User 1" "Test User 2"}
          (set (map :value (:rows (get-view "users" :names-keyed-by-scores {} {:keys [:medium :low]}))))))))
    
(defdbtest use-a-design-view-with-both-map-and-reduce
  (when *clj-view-svr-config*
    (create-document test-document-1)
    (create-document test-document-2)
    (create-document test-document-3)
    (create-document test-document-4)
    (create-view "scores" :sum-of-all-scores
      (with-clj-view-server
        (fn [doc] [nil (:score doc)])
        (fn [keys values _] (apply + values))))
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
                   (fn [doc] (if (re-find #"example\.com$" (:email doc))
                               [nil (:email doc)]))))]
      (is (= #{"robert.jones@example.com" "sarah.parker@example.com"}
            (set (map :value (:rows view))))))))

(defdbtest use-ad-hoc-view-with-javascript-view-server
  (create-document test-document-1)
  (create-document test-document-2)
  (create-document test-document-3)
  (create-document test-document-4)
  (let [view (ad-hoc-view
              {:language "javascript"
               :map      "function(doc, req){if(doc.email.indexOf('test.com')>0)emit(null,doc.email);}"})]
    (is (= #{"john.smith@test.com" "jane.thompson@test.com"}
           (set (map :value (:rows view)))))))

(defdbtest bulk-update-new-documents
  (bulk-update [test-document-1
                test-document-2
                test-document-3
                test-document-4])
  (is (= 4 (:total_rows (get-all-documents)))))

(defdbtest bulk-update-documents
  (bulk-update [test-document-1
                test-document-2
                test-document-3
                test-document-4])
  (bulk-update (map :doc (:rows (get-all-documents {:include_docs true}))) {:updated true})
  (is (every? true? (map #(-> % :doc :updated) (:rows (get-all-documents {:include_docs true}))))))

(defdbtest inline-attachments
  (let [current-path       (.getParent (java.io.File. *file*))
        clojure-img-file   (java.io.File. (str current-path "/clojure.png"))
        couchdb-img-file   (java.io.File. (str current-path "/couchdb.png"))
        document-meta      (create-document test-document-4 [clojure-img-file couchdb-img-file])
        document           (get-document (document-meta :id))]
    (is (= #{:clojure.png :couchdb.png} (set (keys (document :_attachments)))))
    (are (= "image/png" _1)
         (-> document :_attachments :clojure.png :content_type)
         (-> document :_attachments :couchdb.png :content_type))
    (are (= _1 _2)
         (.length clojure-img-file) (-> document :_attachments :clojure.png :length)
         (.length couchdb-img-file) (-> document :_attachments :couchdb.png :length))))

(defdbtest standalone-attachments
  (let [current-path  (.getParent (java.io.File. *file*))
        document-meta (create-document test-document-1)
        document      (get-document (document-meta :id))
        updated-meta  (update-attachment document
                       (str current-path "/couchdb.png") :couchdb-image)
        document      (get-document (document-meta :id) {:attachments true})]
    (is (= :couchdb-image (first (keys (document :_attachments)))))
    (is (= "image/png" (-> document :_attachments :couchdb-image :content_type)))
    (is (contains? (-> document :_attachments :couchdb-image) :data))
    (let [updated-meta (update-attachment document
                         (str current-path "/couchdb.png") :couchdb-image "other/mimetype")
          document (get-document (document-meta :id) {:attachments true})]
      (is (= "other/mimetype" (-> document :_attachments :couchdb-image :content_type))))))

(deftest replicate-a-database
  (try
   (let [source-database (create-database "source_test_db")
         target-database (create-database "target_test_db")]
     (with-db source-database
       (bulk-update [test-document-1
                     test-document-2
                     test-document-3
                     test-document-4]))
     (replicate-database source-database target-database)
     (with-db target-database
       (is (= 4 (:total_rows (get-all-documents))))))
   (finally
    (delete-database "source_test_db")
    (delete-database "target_test_db"))))

(run-tests)