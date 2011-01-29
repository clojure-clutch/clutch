(ns clutch.test-utils
  (:use com.ashafa.clutch.utils
        clojure.test))

(deftest test-map-to-query-str
  (testing "Converting map entries to a query string"
    (are [x y z] (= x (map-to-query-str y z))
         "?a=1&b=2&c=3" {:a 1 :b 2 :c 3} (constantly false)
         "?a=%221%22&b=%222%22&c=%223%22" {:a "1"  :b "2" :c "3"} (constantly true)
         "?a=%221%22&b=%222%22&c=%223%22" {:a "1"  :b "2" :c "3"} (comp not #{})
         "?a=%221%22&b=2&rev=3" {:a "1"  :b "2" :rev "3"} #{:a}))
  
  (testing "Converting map entries without specifying the json predicate fn"
    (is (= "?a=%221%22&b=%222%22&c=%223%22" (map-to-query-str {:a "1"  :b "2" :c "3"})))
    (is (= nil (map-to-query-str {})))))
