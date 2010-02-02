(defproject com.ashafa/clutch "0.1.0-SNAPSHOT"
  :description "A Clojure library for Apache CouchDB."
  :main com.ashafa.clutch.view_server
  :dependencies [[org.clojure/clojure "1.1.0-master-SNAPSHOT"]
                 [org.clojure/clojure-contrib "1.0-SNAPSHOT"]]
  :dev-dependencies [[autodoc "0.7.0"]]
  :resources-path "src/main/resources"
  :test-path "src/test/clojure;src/main/clojure")