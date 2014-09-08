(ns clutch.test.type
  (:use clojure.test
        com.ashafa.clutch
        [test-clutch :only (defdbtest test-database-name test-database-url *test-database*)])
  (:refer-clojure :exclude (conj! assoc! dissoc!)))

(defdbtest create-type
  (let [db (couch *test-database*)]
    ;; :update_seq can change anytime, esp. in cloudant
    (is (= (-> db create! meta :result (dissoc :update_seq))
           (dissoc *test-database* :update_seq)))))

(defdbtest simple
  (let [db (couch *test-database*)]
    (reduce conj! db (for [x (range 100)]
                       {:_id (str x) :a [1 2 x]}))
    (= ["0" {:_id "0" :a [1 2 "0"]}]
       (-> db first (update-in [1] dissoc-meta)))
    (is (= [1 2 68]
           (:a (get db "68"))
           (-> "68" db :a)))
    (is (= 100 (count db)))
    (is (= 68
           (get-in db ["68" :a 2])
           (get-in (into {} db) ["68" :a 2])))
    (dissoc! db "68")
    (is (nil? (get db "68")))
    (is (nil? (db "68")))
    (is (= {:a 5 :b 6}
           (-> db
             (assoc! :foo {:a 5 :b 6})
             meta
             :result
             dissoc-meta)
           (dissoc-meta (:foo db))
           (dissoc-meta (db :foo))))))

(defdbtest use-type-as-db-arg
  (let [db (couch *test-database*)]
    (dotimes [x 100]
      (put-document db {:a x :_id (str x)}))
    (bulk-update db (for [x (range 100)] {:_id (str "x" x) :x x}))
    (is (= 200 (:doc_count (database-info db))))))
