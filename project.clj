(defproject paddleguru/clutch "0.5.0"
  :description "A Clojure library for Apache CouchDB."
  :url "https://github.com/paddleguru/clutch/"
  :license {:name "BSD"
            :url "http://www.opensource.org/licenses/BSD-3-Clause"}
  :dependencies [[org.clojure/clojure "1.7.0-alpha2"]
                 [clj-http "1.0.0"]
                 [cheshire "5.3.1"]
                 [commons-codec "1.6"]
                 [com.cemerick/url "0.1.0"]]
  :profiles {:dev {}
             :1.6.0 {:dependencies [[org.clojure/clojure "1.6.0"]]}}
  :aliases  {"all" ["with-profile" "dev,1.2.0:dev,1.3.0:dev:dev,1.5.0"]}
  :min-lein-version "2.0.0"
  :test-selectors {:default #(not= 'test-docid-encoding (:name %))
                   :all (constantly true)})
