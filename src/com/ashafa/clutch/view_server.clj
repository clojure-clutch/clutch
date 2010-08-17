;; Copyright (c) 2009-2010 Tunde Ashafa
;; All rights reserved.
 
;; Redistribution and use in source and binary forms, with or without
;; modification, are permitted provided that the following conditions
;; are met:
;; 1. Redistributions of source code must retain the above copyright
;; notice, this list of conditions and the following disclaimer.
;; 2. Redistributions in binary form must reproduce the above copyright
;; notice, this list of conditions and the following disclaimer in the
;; documentation and/or other materials provided with the distribution.
;; 3. The name of the author may not be used to endorse or promote products
;; derived from this software without specific prior written permission.
 
;; THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
;; IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
;; OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
;; IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
;; INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
;; NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
;; DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
;; THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
;; (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
;; THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 
 
(ns #^{:author "Tunde Ashafa"}
  com.ashafa.clutch.view-server
  (:gen-class)
  (:require [clojure.contrib.json :as json]))

(def functions (ref []))
 
(defn log
  [message]
  {:log message})
 
(defn reset
  [_]
  (dosync (ref-set functions []))
  true)
 
(defn add-function
  [[function-string]]
  (try
   (let [function (load-string function-string)]
     (if (fn? function)
       (dosync
        (alter functions conj function) true)
       (throw (IllegalArgumentException. "Argument did not evaluate to a function."))))
   (catch IllegalArgumentException error
     {:error {:id "map_compilation_error" :reason (.getMessage error)}})))
 
(defn map-document
  [[document]]
  (for [f @functions]
    (let [results (try (f document)
                       (catch Exception error nil))]
      (vec results))))
 
(defn reduce-values
  ([[function-string & arguments-array :as entire-command]]
     (reduce-values entire-command false))
  ([[function-string & arguments-array] rereduce?]
     (try
      (let [arguments (first arguments-array)
            argument-count (count arguments)
            reduce-functions (map #(load-string %) function-string)
            [keys values] (if rereduce?
                               [nil arguments]
                               (if (> argument-count 1)
                                 (partition argument-count (apply interleave arguments))
                                 [[(first (first arguments))] [(second (first arguments))]]))]
        [true (reduce #(conj %1 (%2 keys values rereduce?)) [] reduce-functions)])
      (catch Exception error
        {:error {:id "reduce_compilation_error" :reason (.getMessage error)}}))))
 
(defn rereduce-values
  [command]
  (reduce-values command true))

(defn filter-changes
  [[rows req user-ctx]]
  (let [f (first @functions)]
    [true (vec (map #(try
                      (f % req)
                      (catch Exception error false)) rows))]))

(defn update-changes
  [[function-string doc request]]
  (try
   (let [function (load-string function-string)]
     (if (fn? function)
       (if-let [[doc response] (function doc request)]
         ["up" doc (if (string? response) {:body response} response)]
         ["up" doc {}])
       (throw (IllegalArgumentException. "Argument did not evaluate to a valid function."))))
   (catch IllegalArgumentException error
     {:error {:id "map_compilation_error" :reason (.getMessage error)}})))

(def handlers {"log"      log
               "reset"    reset
               "add_fun"  add-function
               "map_doc"  map-document
               "reduce"   reduce-values
               "rereduce" rereduce-values
               "filter"   filter-changes
               "update"   update-changes})

(defn run 
  []
  (try
   (flush)
   (let [cmd        (json/read-json (read-line) true)
         return-str (json/json-str ((handlers (first cmd)) (next cmd)))]
     (println return-str))
   (catch Exception e
     (println (json/json-str
               {"log"
                (let [w (java.io.StringWriter.)]
                  (.printStackTrace e (java.io.PrintWriter. w))
                  (.toString w))}))
     (System/exit 1)))
  (recur))
 
(defn -main
  [& args]
  (run))
 
(when *command-line-args*
  (apply -main *command-line-args*))