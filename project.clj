(defproject com.ashafa/clutch "0.2.5-SNAPSHOT"
  :description "A Clojure library for Apache CouchDB."
  :dependencies [[org.clojure/clojure "1.3.0-beta1"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [org.clojure/data.json "0.1.1"]
                 [org.clojure/tools.logging "0.1.2"]]
  :dev-dependencies [#_[autodoc "0.9.0-SNAPSHOT"]  ;; commented deps because they were pulling in clojure 1.1.0...
                     #_[swank-clojure "1.2.1"]
                     #_[lein-clojars "0.5.0-SNAPSHOT"]
                     [lein-multi "1.1.0-SNAPSHOT"]]
  :multi-deps {"clojure-1.2.0" [[org.clojure/clojure "1.2.0"]
                                [org.clojure/clojure-contrib "1.2.0"]
                                [org.clojure/data.json "0.1.1"]
                                [org.clojure/tools.logging "0.1.2"]]})