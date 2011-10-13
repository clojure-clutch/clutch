(ns clutch.test-utils
  (:import java.net.URL)
  (:use com.ashafa.clutch.utils
        clojure.test))

(deftest test-map-to-query-str
  (testing "Converting map entries to a query string"
    (are [x y z] (= x (map-to-query-str y z))
         "a=1&b=2&c=3" {:a 1 :b 2 :c 3} (constantly false)
         "a=%221%22&b=%222%22&c=%223%22" {:a "1"  :b "2" :c "3"} (constantly true)
         "a=%221%22&b=%222%22&c=%223%22" {:a "1"  :b "2" :c "3"} (forgiving-keyset :a 'b "c")
         "a=%221%22&b=2&rev=3" {:a "1"  :b "2" :rev "3"} #{:a}))
  
  (testing "Converting map entries without specifying the json predicate fn"
    (is (= "a=%221%22&b=%222%22&c=%223%22" (map-to-query-str {:a "1"  :b "2" :c "3"})))
    (is (= nil (map-to-query-str {})))))

(deftest url-roundtripping
  (let [aurl (url "https://username:password@some.host.com/database?query=string")]
    (is (= "https://username:password@some.host.com:443/database?query=string" (str aurl)))
    (is (== 443 (-> aurl str URL. .getPort)))
    (is (#{nil -1} (:port aurl)))
    (is (= "username" (:username aurl)))
    (is (= "password" (:password aurl)))
    (is (= "username:password" (url-creds aurl)))
    (is (= "https://username:password@some.host.com:443/" (str (server-url aurl))))
    (is (== 9000 (-> "http://host:9000/database" url str URL. .getPort)))
    (is (== 5984 (-> "http://host/database" url str URL. .getPort))))
  (testing "using only database name"
    (is (= "http://localhost:5984/mydatabase" (str (url "mydatabase"))))))

(deftest url-segments
  (is (= "http://localhost:5984/foo/bar" (str (url "foo" "bar"))))
  (is (= "http://localhost:5984/foo/bar/baz" (str (url "foo" "bar" "baz")))))