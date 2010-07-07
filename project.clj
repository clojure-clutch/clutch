(defproject com.ashafa/clutch "0.2.1"
  :description "A Clojure library for Apache CouchDB."
  :main com.ashafa.clutch.view_server
  :dependencies [[org.clojure/clojure "1.1.0"]
                 [org.clojure/clojure-contrib "1.1.0"]]
  :dev-dependencies [[autodoc "0.7.0"]
                     [lein-clojars "0.5.0-SNAPSHOT"]]
  :source-path "src/main/clojure"
  :resources-path "src/main/resources"
  :test-path "src/test/clojure;src/main/clojure")