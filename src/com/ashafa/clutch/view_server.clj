;; Copyright (c) 2009 Tunde Ashafa
;; All rights reserved.

;; Redistribution and use in source and binary forms, with or without
;; modification, are permitted provided that the following conditions
;; are met:
;; 1. Redistributions of source code must retain the above copyright
;;    notice, this list of conditions and the following disclaimer.
;; 2. Redistributions in binary form must reproduce the above copyright
;;    notice, this list of conditions and the following disclaimer in the
;;    documentation and/or other materials provided with the distribution.
;; 3. The name of the author may not be used to endorse or promote products
;;    derived from this software without specific prior written permission.

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


(ns com.ashafa.clutch.view-server
  (:require [clojure.contrib.json.read :as json-read]
            [clojure.contrib.json.write :as json-write]))


(def functions (ref []))

(defn log 
  [message]
  {:log message})

(defn reset 
  [_]
  (def functions (ref [])) true)

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
  (reduce #(conj %1 [(try (or (%2 document) []) (catch Exception error []))]) [] @functions))

(defn reduce-values
  ([[function-string & arguments-container :as entire-command]]
     (reduce-values entire-command false))
  ([[function-string & arguments-container] rereduce?]
     (try
      (let [arguments       (first arguments-container)
            reduce-function (map #(load-string %) function-string)
            [keys values]   (if rereduce?
                              [nil arguments]
                              (partition (count arguments) (apply interleave arguments)))]
        [true (reduce #(conj %1 (%2 keys values rereduce?)) [] reduce-function)])
      (catch Exception error
        {:error {:id "reduce_compilation_error" :reason (.getMessage error)}}))))

(defn rereduce-values 
  [command]
  (reduce-values command true))

(def handlers {"log"      log
               "reset"    reset
               "add_fun"  add-function
               "map_doc"  map-document
               "reduce"   reduce-values
               "rereduce" rereduce-values})

(defn run 
  []
  (try
   (flush)
   (let [cmd        (binding [json-read/*json-keyword-keys* true]
                      (json-read/read-json (read-line)))
         return-str (json-write/json-str ((handlers (first cmd)) (next cmd)))]
     (println return-str))
   (catch Exception e (System/exit 1)))
  (recur))

(run)