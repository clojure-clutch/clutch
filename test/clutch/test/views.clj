(ns clutch.test.views
  (:require (com.ashafa.clutch
              [view-server :as view-server]
              [utils :as utils]))
  (:use clojure.test
        com.ashafa.clutch
        [test-clutch :only (defdbtest test-database-name test-host
                             test-docs test-database-url *test-database*)])
  (:refer-clojure :exclude (conj! assoc! dissoc!)))

(declare ^{:dynamic true} *clj-view-svr-config*)

; don't squash existing canonical "clojure" view server config
(def ^{:private true} view-server-name :clutch-test)

(.addMethod view-transformer
            view-server-name
            (get-method view-transformer :clojure))

(use-fixtures
  :once
  #(binding [*clj-view-svr-config* (try
                                     (when (re-find #"localhost" test-host)
                                       (configure-view-server (utils/url test-host)
                                                              (view-server/view-server-exec-string)
                                                              :language view-server-name))
                                     (catch java.io.IOException e (.printStackTrace e)))]
     (when-not *clj-view-svr-config*
       (println "Could not autoconfigure clutch view server,"
                "skipping tests that depend upon it!")
       (println "(This is normal if you're testing against e.g. Cloudant)")
       (println (view-server/view-server-exec-string))
       (println))
     (%)))

(defdbtest lazy-view-results
  (bulk-update (map (comp (partial hash-map :_id) str) (range 50000)))
  (is (= {:total_rows 50000 :offset 0} (meta (all-documents))))
  (let [time #(let [t (System/currentTimeMillis)]
                [(%) (- (System/currentTimeMillis) t)])
        [f tf] (time #(first (all-documents)))
        [l tl] (time #(last (all-documents)))]
    ; any other way to check that the returned seq is properly lazy?
    (is (< 10 (/ tl tf)))))

(defdbtest create-a-design-view
  (when *clj-view-svr-config*
    (let [view-document (save-view "users" 
                                   (view-server-fns view-server-name
                                     {:names-with-score-over-70
                                      {:map #(if (> (:score %) 70) [[nil (:name %)]])}}))]
      (is (map? (-> (get-document (view-document :_id)) :views :names-with-score-over-70))))))

(defdbtest use-a-design-view-with-spaces-in-key
  (when *clj-view-svr-config*
    (bulk-update test-docs)
    (save-view "users"
               (view-server-fns view-server-name
                                {:names-and-scores
                                 {:map (fn [doc] [[(:name doc) (:score doc)]])}}))
    (is (= [98]
             (map :value (get-view "users" :names-and-scores {:key "Jane Thompson"}))))))

(defdbtest use-a-design-view-with-map-only
  (when *clj-view-svr-config*
    (bulk-update test-docs)
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
    (bulk-update test-docs)
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
    (bulk-update test-docs)
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
    (bulk-update test-docs)
    (let [view (ad-hoc-view
                 (view-server-fns view-server-name
                   {:map (fn [doc] (if (re-find #"example\.com$" (:email doc))
                                   [[nil (:email doc)]]))}))]
      (is (= #{"robert.jones@example.com" "sarah.parker@example.com"}
            (set (map :value view)))))))

(defdbtest use-ad-hoc-view-with-javascript-view-server
  (if (re-find #"cloudant" test-host)
    (println "skipping ad-hoc view test; not supported by Cloudant")
    (do
      (bulk-update test-docs)
      (let [view (ad-hoc-view
                   (view-server-fns :javascript
                                    {:map "function(doc){if(doc.email.indexOf('test.com')>0)emit(null,doc.email);}"}))]
        (is (= #{"john.smith@test.com" "jane.thompson@test.com"}
               (set (map :value view))))))))

;; TODO this sucks; can we get leiningen to just not test certain namespaces, and keep the
;; cljs view tests there to avoid this conditional and the eval junk?!
(if (neg? (compare (clojure-version) "1.3.0"))
  (deftest no-cljs-error
    (is (thrown-with-msg? UnsupportedOperationException #"Could not load com.ashafa.clutch.cljs-views"
          (view-transformer :cljs))))
  ;; need the eval to work around view-server-fns macroexpansion in 1.2, which will end
  ;; up calling (view-transformer :cljs)
  (eval '(let [cljs-view-result [{:id "x", :key ["x" 0], :value 1}
                                 {:id "x", :key ["x" 1], :value 2}
                                 {:id "y", :key ["y" 0], :value 1}
                                 {:id "y", :key ["y" 1], :value 2}
                                 {:id "y", :key ["y" 2], :value 3}]]
    (defdbtest cljs-simple
      (bulk-update [{:_id "x" :count 2}
                    {:_id "y" :count 3}])
      (save-view "cljs-views"
        (view-server-fns :cljs
          {:enumeration {:map #(dotimes [x (aget % "count")]
                                 (js/emit (js/Array (aget % "_id") x) (inc x)))}}))
      (is (= cljs-view-result
             (get-view "cljs-views" :enumeration))))
    
    (defdbtest cljs-inline-namespace
      (bulk-update [{:_id "x" :count 2}
                    {:_id "y" :count 3}])
      (save-view "namespaced-cljs-views"
        (view-server-fns {:language :cljs
                          :main 'inline.namespace.couchview/main}
          {:enumeration {:map [(ns inline.namespace.couchview)
                               (defn view-key
                                 [doc x]
                                 (js/Array (aget doc "_id") x))
                               (defn ^:export main
                                 [doc]
                                 (dotimes [x (aget doc "count")]
                                   (js/emit (view-key doc x) (inc x))))]}}))
      (is (= cljs-view-result
             (get-view "namespaced-cljs-views" :enumeration))))

