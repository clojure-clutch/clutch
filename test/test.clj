(ns clutch
  (:use com.ashafa.clutch
        (clojure.contrib [test-is :as test-is])))

(set-clutch-defaults! {:language "clojure"})

(def test-doc-1 {:name  "John Smith"
                 :email "john.smith@test.com"
                 :score 65})

(def test-doc-2 {:name  "Jane Thompson"
                 :email "jane.thompson@test.com"
                 :score 98})

(def test-doc-3 {:name  "Robert Jones"
                 :email "robert.jones@example.com"
                 :score 80})

(def test-doc-4 {:name  "Sarah Parker"
                 :email "sarah.parker@example.com"
                 :score 59})

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
    (is (= true (:ok test-database)))
    (is ((set (all-couchdb-databases)) (:name test-database)))
    (is (= (:name test-database) (:db_name (database-info test-database))))
    (is (= {:ok true} (delete-database test-database)))))

(defdbtest create-a-document
  (is (contains? (create-document test-doc-1) :id)))

(defdbtest create-a-document-with-id
  (is (= "my_id" (:id (create-document "my_id" test-doc-2)))))

(defdbtest get-a-document
  (let [doc-meta (create-document test-doc-3)]
    (is (= (test-doc-3 :name) (:name (get-document (doc-meta :id)))))))

(defdbtest update-a-document
  (let [id (:id (create-document test-doc-4))]
    (update-document (get-document id) {:email "test@example.com"})
    (is (= "test@example.com" (:email (get-document id))))))

(defdbtest update-a-document-with-a-function
  (let [id (:id (create-document test-doc-4))]
    (update-document (get-document id) (partial + 4) [:score])
    (is (= 63 (:score (get-document id))))))

(defdbtest delete-a-document
  (create-document "my_id" test-doc-2)
  (is (true? (:ok (delete-document (get-document "my_id")))))
  (is (nil? (get-document "my_id"))))

(defdbtest get-all-documents-meta
  (create-document test-doc-1)
  (create-document test-doc-2)
  (create-document test-doc-3)
  (is (= 3 (:total_rows (get-all-documents)))))

(defdbtest get-all-documents-using-query-params
  (create-document 1 test-doc-1)
  (create-document 2 test-doc-2)
  (create-document 3 test-doc-3)
  (is (= "Robert Jones" (-> (get-all-documents {:include_docs true :descending true})
                            :rows first :doc :name))))

(defdbtest create-a-design-view
  (let [design-doc-meta (create-view "users" 
                                     :names-with-score-over-70 
                                     (with-clj-view-server 
                                      #(if (> (:score %) 70) [nil (:name %)])))]
    (is (map? (-> (get-document (design-doc-meta :id)) :views :names-with-score-over-70)))))

(defdbtest use-a-design-view-with-only-map
  (create-document test-doc-1)
  (create-document test-doc-2)
  (create-document test-doc-3)
  (create-document test-doc-4)
  (create-view "users" :names-with-score-over-70 
               (with-clj-view-server
                #(if (> (:score %) 70) [nil (:name %)])))
  (is (= #{"Robert Jones" "Jane Thompson"}
         (set (map :value (:rows (get-view "users" :names-with-score-over-70)))))))

(defdbtest use-a-design-view-with-both-map-and-reduce
  (create-document test-doc-1)
  (create-document test-doc-2)
  (create-document test-doc-3)
  (create-document test-doc-4)
  (create-view "scores" :sum-of-all-scores 
               (with-clj-view-server
                (fn [doc] [nil (:score doc)])
                (fn [keys values _] (apply + values))))
  (is (= 302 (-> (get-view "scores" :sum-of-all-scores) :rows first :value))))

(defdbtest use-ad-hoc-view
  (create-document test-doc-1)
  (create-document test-doc-2)
  (create-document test-doc-3)
  (create-document test-doc-4)
  (is (= #{"robert.jones@example.com" "sarah.parker@example.com"}
         (set (map :value (:rows (ad-hoc-view 
                                  (with-clj-view-server
                                   (fn [doc] (if (re-find #"example\.com$" (:email doc))
                                              [nil (:email doc)]))))))))))

(defdbtest use-ad-hoc-view-with-javascript-view-server
  (create-document test-doc-1)
  (create-document test-doc-2)
  (create-document test-doc-3)
  (create-document test-doc-4)
  (is (= #{"john.smith@test.com" "jane.thompson@test.com"}
         (set (map :value (:rows (ad-hoc-view
                                  {:language "javascript"
                                   :map      "function(doc){if(doc.email.indexOf('test.com')>0)emit(null,doc.email);}"})))))))

(defdbtest bulk-insert-documents
  (bulk-insert-update [test-doc-1
                       test-doc-2
                       test-doc-3
                       test-doc-4])
  (is (= 4 (:total_rows (get-all-documents)))))

(defdbtest bulk-update-documents
  (bulk-insert-update [test-doc-1
                       test-doc-2
                       test-doc-3
                       test-doc-4])
  (bulk-insert-update (map :doc (:rows (get-all-documents {:include_docs true}))) {:updated true})
  (is (every? true? (map #(-> % :doc :updated) (:rows (get-all-documents {:include_docs true}))))))

(run-tests)