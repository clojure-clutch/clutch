(ns clutch.changes
  (:use com.ashafa.clutch
        [test-clutch :only (defdbtest test-docs test-database-name)]
        clojure.test)
  (:refer-clojure :exclude (conj! assoc! dissoc!)))

(defn- wait-for-condition
  [f desc]
  (let [wait-condition (loop [waiting 0]
                         (cond
                           (f) true
                           (>= waiting 20) false
                           :else (do
                                   (Thread/sleep 1000)
                                   (recur (inc waiting)))))]
    (when-not (is wait-condition desc)
      (throw (IllegalStateException. desc)))
    wait-condition))

(deftest simple
  (let [db (get-database (test-database-name "create-type"))]
    (with-db db
      (try
        (let [a (change-agent)
              updates (atom [])]
          (add-watch a :watcher (fn [_ _ _ x] (when x (swap! updates conj x))))
          (start-changes a)
          (bulk-update (map #(hash-map :_id (str %)) (range 4)))
          (delete-document (get-document "2"))
          (update-document (assoc (get-document "1") :assoc :a 5))
          (delete-database)
          (wait-for-condition #(-> @updates last :last_seq) "Updates not received")
          (is (= [{:id "0"} {:id "1"} {:id "2"} {:id "3"} {:id "2" :deleted true} {:id "1"}]
                 (->> @updates
                   drop-last
                   (map #(dissoc % :changes :seq))))))
        (finally
          (delete-database))))))

(defdbtest restarting-changes
  (let [a (change-agent)
          updates (atom [])
          docs (map #(hash-map :_id (str %)) (range 8))]
      (add-watch a :watcher (fn [_ _ _ x] (when x (swap! updates conj x))))
      (start-changes a)
      (bulk-update (take 4 docs))
      (wait-for-condition #(= 4 (count @updates)) "Updates not received")
      (stop-changes a)
      
      (put-document (nth docs 4))
      (Thread/sleep 1000)
      (is (= 4 (count @updates)))
      
      (start-changes a)
      (wait-for-condition #(= 5 (count @updates)) "Updates not received")
      
      (bulk-update (drop 5 docs))
      (wait-for-condition #(= 8 (count @updates)) "Updates not received")
      (is (= 8 (count @updates)))))

(defdbtest changes-filter
  (save-filter "scores"
               (view-server-fns :javascript
                 {:more-than-50 "function (doc, req) { return doc['score'] > 50; }"}))
  (let [a (change-agent :filter "scores/more-than-50")
        updates (atom [])]
    (add-watch a :watcher (fn [_ _ _ x] (when x (swap! updates conj x))))
    (start-changes a)
    (bulk-update [{:score 22 :_id "x"} {:score 79 :_id "y"}])
    (Thread/sleep 1000)
    (is (= 1 (count @updates)))
    (is (= {:id "y"} (-> @updates
                       first
                       (select-keys [:id]))))))

(defdbtest changes-filter-with-params
  (save-filter "scores"
               (view-server-fns :javascript
                 {:more-than-x "function (doc, req) { return doc['score'] > req.query.score; }"}))
  (let [a (change-agent :filter "scores/more-than-x" :score 25)
        updates (atom [])]
    (add-watch a :watcher (fn [_ _ _ x] (when x (swap! updates conj x))))
    (start-changes a)
    (bulk-update [{:score 22 :_id "x"} {:score 79 :_id "y"} {:score 27 :_id "z"}])
    (Thread/sleep 1000)
    (is (= 2 (count @updates)))
    (is (= [{:id "y"} {:id "z"}] (map #(select-keys % [:id]) @updates)))))
