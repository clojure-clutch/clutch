(defproject com.ashafa/clutch "0.4.0-SNAPSHOT"
  :description "A Clojure library for Apache CouchDB."
  :url "https://github.com/clojure-clutch/clutch/"
  :license {:name "BSD"
            :url "http://www.opensource.org/licenses/BSD-3-Clause"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 
                 [clj-http "0.4.1-SNAPSHOT"]
                 [cheshire "4.0.0"]
                 [commons-codec "1.6"]
                 [com.cemerick/url "0.0.5"]]
  :profiles {:dev {}
             :1.2.0 {:dependencies [[org.clojure/clojure "1.2.0"]]}
             :1.3.0 {:dependencies [[org.clojure/clojure "1.3.0"]]}}
  :aliases  { "all" ["with-profile" "dev,1.2.0:dev,1.3.0:dev"] }
  :min-lein-version "2.0.0"
  :test-selectors {:default (constantly true)
                   :no-encoding #(not= 'test-docid-encoding (:name %))})
