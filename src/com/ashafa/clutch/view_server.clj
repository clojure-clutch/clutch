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
            [clojure.contrib.json.write :as json-write])
  (:import  (java.util.concurrent ArrayBlockingQueue)))


(def functions (ref []))

(defn log [message]
  (println (json-write/json-str {:log message})))

(defn reset [_]
  (def functions (ref [])) true)

(defn add-fun [[s]]
  (try 
   (let [function (load-string s)]
     (if (fn? function)
       (dosync 
        (alter functions conj function) true)
       (throw (IllegalArgumentException. "Argument did not evaluate to a function."))))
   (catch Exception e
     {:error {:id "map_compilation_error" :reason (.getMessage e)}})))

(defn map-doc [[doc]]
  (reduce #(conj %1 [(try (or (%2 doc) []) (catch Exception e []))]) [] @functions))

(defn reduce-vals 
  ([[fns & args-vec :as entire-cmd]]
     (reduce-vals entire-cmd false))
  ([[fns & args-vec] rereduce?]
     (try
      (let [args          (first args-vec)
            reduce-fns    (map #(load-string %) fns)
            [keys values] (if rereduce?
                            [nil args]
                            (partition (count args) (apply interleave args)))]
        [true (reduce #(conj %1 (%2 keys values rereduce?)) [] reduce-fns)])
      (catch Exception e
        {:error {:id "reduce_compilation_error" :reason (.getMessage e)}}))))

(defn rereduce-vals [cmd]
  (reduce-vals cmd true))

(def handlers {"log"      log
               "reset"    reset
               "add_fun"  add-fun
               "map_doc"  map-doc
               "reduce"   reduce-vals
               "rereduce" rereduce-vals})

(defn run [queue]
  (if-let [input (read-line)]
    (try
     (binding [json-read/*json-keyword-keys* true]
       (.put queue (json-read/read-json input)))
     (let [cmd        (.take queue)
           return-str (json-write/json-str ((handlers (first cmd)) (next cmd)))]
         (println return-str))
     (catch Exception e))
     (Thread/sleep 1))
  (recur queue))

(run (ArrayBlockingQueue. 1))