(defproject paddleguru/clutch "0.4.0"
  :description "A Clojure library for Apache CouchDB."
  :url "https://github.com/paddleguru/clutch/"
  :license {:name "BSD"
            :url "http://www.opensource.org/licenses/BSD-3-Clause"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [clj-http "0.7.7"]
                 [cheshire "5.2.0"]
                 [commons-codec "1.6"]
                 [com.cemerick/url "0.1.0"]]
  :profiles {:dev {}
             :1.3.0 {:dependencies [[org.clojure/clojure "1.3.0"]]}
             :1.4.0 {:dependencies [[org.clojure/clojure "1.4.0"]]}}
  :aliases  {"all" ["with-profile" "dev,1.2.0:dev,1.3.0:dev:dev,1.5.0"]}
  :min-lein-version "2.0.0"
  :test-selectors {:default #(not= 'test-docid-encoding (:name %))
                   :all (constantly true)})
