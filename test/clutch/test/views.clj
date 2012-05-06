(ns clutch.test.views
  (:use clojure.test
        com.ashafa.clutch
        [test-clutch :only (defdbtest test-database-name test-database-url *test-database*)])
  (:refer-clojure :exclude (conj! assoc! dissoc!)))

(defdbtest cljs-simple
  (bulk-update [{:_id "x" :count 2}
                {:_id "y" :count 3}])
  (save-view "cljs-views"
    (view-server-fns :cljs
      {:enumeration {:map #(dotimes [x (aget % "count")]
                             (js/emit (js/Array (aget % "_id") x) (inc x)))}}))
  (is (= '#{{:id x, :key [x 0], :value 1}
            {:id x, :key [x 1], :value 2}
            {:id y, :key [y 0], :value 1}
            {:id y, :key [y 1], :value 2}
            {:id y, :key [y 2], :value 3}})
      (get-view "cljs-views" :enumeration)))

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
                             (dotimes [x (aget % "count")]
                               (js/emit (view-key doc x) (inc x))))]}}))
  (is (= '#{{:id x, :key [x 0], :value 1}
            {:id x, :key [x 1], :value 2}
            {:id y, :key [y 0], :value 1}
            {:id y, :key [y 1], :value 2}
            {:id y, :key [y 2], :value 3}})
      (get-view "namespaced-cljs-views" :enumeration)))

