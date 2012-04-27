(defproject com.ashafa/clutch "0.3.1"
  :description "A Clojure library for Apache CouchDB."
  :url "http://github.com/ashafa/clutch"
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [org.clojure/data.json "0.1.2"]
                 [org.clojure/tools.logging "0.1.2"]
                 [commons-codec "1.6"]
                 [com.cemerick/url "0.0.5"]]
  :profiles {:dev {:plugins [[lein-clojars "0.8.0"]]}
             :1.2 {:dependencies [[org.clojure/clojure "1.2.0"]]}
             :1.4 {:dependencies [[org.clojure/clojure "1.4.0"]]}}
  :aliases  { "all" ["with-profile" "dev,1.2:dev:dev,1.4"] }
  :min-lein-version "2.0.0"
  :test-selectors {:default (constantly true)
                   :no-encoding #(not= 'test-docid-encoding (:name %))})
