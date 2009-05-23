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