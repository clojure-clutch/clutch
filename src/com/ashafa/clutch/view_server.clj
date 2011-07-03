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
  (:require [clojure.data.json :as json]))

(def functions (ref []))

(def cached-views (ref {}))

(defn log
  [message]
  ["log" message])
 
(defn reset
  [_]
  (dosync (ref-set functions []))
  true)
 
(defn add-function
  [[function-string]]
  (let [function (load-string function-string)]
    (if (fn? function)
      (dosync
       (alter functions conj function) true)
      ["error" "map_compilation_error" "Argument did not evaluate to a function."])))
 
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
                                 [[(ffirst arguments)] [(second (first arguments))]]))]
        [true (reduce #(conj %1 (%2 keys values rereduce?)) [] reduce-functions)])
      (catch Exception error
        ["error" "reduce_compilation_error" (.getMessage error)]))))
 
(defn rereduce-values
  [command]
  (reduce-values command true))

(defn filter-changes
  [func ddoc [ddocs request]]
  [true (vec (map #(or (and (func % request) true) false) ddocs))])

(defn update-changes
  [func ddoc args]
  (if-let [[ddoc response] (apply func args)]
      ["up" ddoc (if (string? response) {:body response} response)]
      ["up" ddoc {}]))

(def ddoc-handlers {"filters" filter-changes
                    "updates" update-changes})

(defn ddoc
  [arguments]
  (let [ddoc-id (first arguments)]
    (if (= "new" ddoc-id)
      (let [ddoc-id (second arguments)]
        (dosync
         (alter cached-views assoc ddoc-id (nth arguments 2)))
        true)
      (if-let [ddoc (@cached-views ddoc-id)]
        (let [func-path (map keyword (second arguments))
              command   (first (second arguments))
              func-args (nth arguments 2)]
          (if-let [handle (ddoc-handlers command)]
            (let [func-key (last func-path)
                  cfunc    (get-in ddoc func-path)
                  func     (if-not (fn? cfunc) (load-string cfunc) cfunc)]
              (if-not (fn? cfunc)
                (dosync
                 (alter cached-views assoc-in [ddoc-id func-key] func)))
              (apply handle [func (@cached-views ddoc-id) func-args]))
            ["error" "unknown_command" (str "Unknown ddoc '" command  "' command")]))
        ["error" "query_protocol_error" (str "Uncached design doc id: '" ddoc-id "'")]))))

(def handlers {"log"      log
               "ddoc"     ddoc
               "reset"    reset
               "add_fun"  add-function
               "map_doc"  map-document
               "reduce"   reduce-values
               "rereduce" rereduce-values})

(defn run 
  []
  (try
   (flush)
   (when-let [line (read-line)] ; don't throw an error if we just get EOF
     (let [input      (json/read-json line true)
           command    (first input)
           handle     (handlers command)
           return-str (if handle
                        (handle (next input))
                        ["error" "unknown_command" (str "No '" command "' command.")])]
       (println (json/json-str return-str))))
   (catch Exception e
     (println (json/json-str ["fatal" "fatal_error" (let [w (java.io.StringWriter.)]
                                                      (.printStackTrace e (java.io.PrintWriter. w))
                                                      (.toString w))]))
     (System/exit 1)))
  (recur))
 

(defn -main
  [& args]
  (run))
