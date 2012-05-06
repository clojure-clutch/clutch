(ns clutch.test.changes
  (:use com.ashafa.clutch
        [test-clutch :only (defdbtest test-docs test-database-name)]
        clojure.test)
  (:refer-clojure :exclude (conj! assoc! dissoc!)))

(defn- wait-for-condition
  [f desc]
  (let [wait-condition (loop [waiting 0]
                         (cond
                           (f) true
                           (>= waiting 10) false
                           :else (do
                                   (Thread/sleep 1000)
                                   (recur (inc waiting)))))]
    (when-not (is wait-condition desc)
      (throw (IllegalStateException. desc)))
    wait-condition))

(deftest simple-agent
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

(defdbtest can-stop-change-agent
  (let [a (change-agent)
        updates (atom [])]
    (add-watch a :watcher (fn [_ _ _ x] (when x (swap! updates conj x))))
    (start-changes a)
    (bulk-update (take 3 test-docs))
    (wait-for-condition #(= 3 (count @updates)) "3 updates not received")
    (stop-changes a)
    (put-document (last test-docs))
    (Thread/sleep 2000)
    (is (= 3 (count @updates)))))

#_(defdbtest restarting-changes
  (let [a (change-agent)
          updates (atom [])
          docs (map #(hash-map :_id (str %)) (range))]
      (add-watch a :watcher (fn [_ _ _ x] (println "f" x) (when x (swap! updates conj x))))
      (start-changes a)
      (bulk-update (take 4 docs))
      (wait-for-condition #(= 4 (count @updates)) "4 updates not received")
      (stop-changes a)
      
      (put-document (nth docs 4))
      (Thread/sleep 1000)
      (is (= 4 (count @updates)))
      
      (start-changes a)
      (wait-for-condition #(= 5 (count @updates)) "1 update not received")
      
      (bulk-update (->> docs (drop 5) (take 3)))
      (wait-for-condition #(= 8 (count @updates)) "3 updates not received")
      (is (= 8 (count @updates)))
      
      (testing "start-changes with :since option"
            (println (.getQueueCount a) (-> a meta :com.ashafa.clutch/state))
        (stop-changes a)
        (reset! updates [])
            (println (.getQueueCount a) (-> a meta :com.ashafa.clutch/state))
        (start-changes a :since 0)
        (put-document (->> docs (drop 8) first))
        (Thread/sleep 10000)
            (println (.getQueueCount a) (-> a meta :com.ashafa.clutch/state))
        (wait-for-condition
          #(do (println (count @updates) (map :seq @updates)) (= 9 (count @updates)))
          "change agent probably not stopped straight away, still waiting for a change to restart with new params")
        (bulk-update (->> docs (drop 9) (take 7)))
        (wait-for-condition #(= 16 (count @updates)) "7 updates not received")
        (is (= 16 (count @updates))))
      
      (testing "changes-running? predicate"
        (is (changes-running? a))
        (is (instance? Boolean (changes-running? a)))
        (stop-changes a)
        (is (not (changes-running? a)))
        (is (instance? Boolean (changes-running? a))))))

(defdbtest filtered-change-agent
  (save-filter "scores"
               (view-server-fns :javascript
                 {:more-than-50 "function (doc, req) { return doc['score'] > 50; }"}))
  (let [a (change-agent :filter "scores/more-than-50")
        updates (atom [])]
    (add-watch a :watcher (fn [_ _ _ x] (when x (swap! updates conj x))))
    (start-changes a)
    (bulk-update [{:score 22 :_id "x"} {:score 79 :_id "y"}])
    (wait-for-condition #(= 1 (count @updates)) "1 update not received")
    (is (= 1 (count @updates)))
    (is (= {:id "y"} (-> @updates
                       first
                       (select-keys [:id]))))))

(defdbtest parameterized-filtered-change-agent
  (save-filter "scores"
               (view-server-fns :javascript
                 {:more-than-x "function (doc, req) { return doc['score'] > req.query.score; }"}))
  (let [a (change-agent :filter "scores/more-than-x" :score 25)
        updates (atom [])]
    (add-watch a :watcher (fn [_ _ _ x] (when x (swap! updates conj x))))
    (start-changes a)
    (bulk-update [{:score 22 :_id "x"} {:score 79 :_id "y"} {:score 27 :_id "z"}])
    (wait-for-condition #(= 2 (count @updates)) "2 update not received")
    (is (= 2 (count @updates)))
    ;; order-insensitive here because cloudant/bigcouch can yield changes in any order
    (is (= #{{:id "y"} {:id "z"}}
           (->> @updates
             (map #(select-keys % [:id]))
             set)))))
