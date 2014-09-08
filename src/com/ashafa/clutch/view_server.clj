(ns com.ashafa.clutch.view-server
  (:require [cheshire.core :as json]))

(defn view-server-exec-string
  "Generates a string that *should* work to configure a clutch view server
   within your local CouchDB instance based on the current process'
   java.class.path system property.  Assumes that `java` is on CouchDB's
   PATH."
  []
  (format "java -cp \"%s\" clojure.main -i @/com/ashafa/clutch/view_server.clj -e \"(com.ashafa.clutch.view-server/-main)\""
          (System/getProperty "java.class.path")))

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
     (let [input      (json/parse-string line true)
           command    (first input)
           handle     (handlers command)
           return-str (if handle
                        (handle (next input))
                        ["error" "unknown_command" (str "No '" command "' command.")])]
       (println (json/generate-string return-str))))
   (catch Exception e
     (println (json/generate-string
                ["fatal" "fatal_error" (let [w (java.io.StringWriter.)]
                                         (.printStackTrace e (java.io.PrintWriter. w))
                                         (.toString w))]))
     (System/exit 1)))
  (recur))


(defn -main
  [& args]
  (run))
