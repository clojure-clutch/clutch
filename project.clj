(defproject com.ashafa/clutch "0.3.1-SNAPSHOT"
  :description "A Clojure library for Apache CouchDB."
  :url "http://github.com/ashafa/clutch"
  :dependencies [[org.clojure/clojure "[1.2.0,)"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [org.clojure/data.json "0.1.2"]
                 [org.clojure/tools.logging "0.1.2"]
                 [commons-codec "1.6"]
                 [com.cemerick/url "0.0.3"]]
  :dev-dependencies [[swank-clojure "1.3.3"]
                     [lein-clojars "0.7.0"]
                     [lein-multi "1.1.0-SNAPSHOT"]]
  :multi-deps {"clojure-1.3.0" [[org.clojure/clojure "1.3.0"]
                                [org.clojure/clojure-contrib "1.2.0"]
                                [org.clojure/data.json "0.1.2"]
                                [org.clojure/tools.logging "0.1.2"]]}
  :test-selectors {:default (constantly true)
                   :no-encoding #(not= 'test-docid-encoding (:name %))})