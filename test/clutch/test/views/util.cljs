(ns clutch.test.views.util)

(defn enumerate-count
  "A trivial fn that is used by one of the ClojureScript view tests to ensure that
   requires and such work as expected."
  [doc]
  (->> (aget doc "count")
    range
    (map vector (repeat (aget doc "_id")))))