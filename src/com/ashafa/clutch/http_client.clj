(ns com.ashafa.clutch.http-client
  (:require [clj-http.client :as http]
            [cheshire.core :as json]

            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.ashafa.clutch.utils :as utils])
  (:use [cemerick.url :only (url)]
        [slingshot.slingshot :only (throw+ try+)])
  (:import  (java.io IOException InputStream InputStreamReader PushbackReader)
            (java.net URL URLConnection HttpURLConnection MalformedURLException)))


(def ^{:private true} version "0.3.0")
(def ^{:private true} user-agent (str "com.ashafa.clutch/" version))
(def ^{:dynamic true} *default-data-type* "application/json")
(def ^{:dynamic true} *configuration-defaults* {:socket-timeout 0
                                                :conn-timeout 5000
                                                :follow-redirects true
                                                :save-request? true
                                                :as :json})

(def  ^{:doc "When thread-bound to any value, will be reset! to the
complete HTTP response of the last couchdb request."
        :dynamic true}
     *response* nil)

(defmacro fail-on-404
  [db expr]
  `(let [f# #(let [resp# ~expr]
               (if (= 404 (:status *response*))
                 (throw (IllegalStateException. (format "Database %s does not exist" ~db)))
                 resp#))]
     (if (thread-bound? #'*response*)
       (f#)
       (binding [*response* nil] (f#)))))

(defn- set!-*response*
  [response]
  (when (thread-bound? #'*response*) (set! *response* response))
  response)

(defn- connect
  [request]
  (let [configuration (merge *configuration-defaults* request)
        data (:data request)]
    (try+
      (let [resp (http/request (merge configuration
                                      {:url (str request)}
                                      (when data {:body data})
                                      (when (instance? InputStream data)
                                        {:length (:data-length request)})))]
        (set!-*response* resp))
      (catch identity ex
        (if (map? ex)
          (do
            (set!-*response* ex)
            (when-not (== 404 (:status ex))
              (throw+ ex)))
          (throw+ ex))))))

(defn- configure-request
  [method url {:keys [data data-length content-type headers]}]
  (assoc url
    :method method
    :data (if (map? data) (json/generate-string data) data)
    :data-length data-length
    :headers (merge {"Content-Type" (or content-type *default-data-type*)
                     "User-Agent" user-agent
                     "Accept" "*/*"}
                    headers)))

(defn couchdb-request*
  [method url & {:keys [data data-length content-type headers] :as opts}]
  (connect (configure-request method url opts)))

(defn couchdb-request
  "Same as couchdb-request*, but returns only the :body of the HTTP response."
  [& args]
  (:body (apply couchdb-request* args)))

(defn response-handle-if-parsed*
  "Was the response already parsed as JSON? send that. Otherwise,
  parse the line as JSON."
  [line]
  (if (and (string? line) (.startsWith line "{"))
    (json/parse-string line true)
    (if (not (string? line))
      line)
    ))

(defn response-parse*
  "Separate rows|results from the headers"
  [s]
  (json/parse-string (str/replace s #",?\"(rows|results)\":\[\s*$" "}") true))  ; TODO this is going to break eventually :-/

(defn response-with-header*
  "Handle sorting out what is the response."
  [lines]
  [(if (empty? (rest lines)) (:rows (response-parse* (first lines))) (rest lines))
   (-> (first lines)
       (response-parse*))]
  )

(defn response-no-header*
  "No header? Just send back lines."
  [lines]
  [lines nil])

(defn lazy-view-seq
  "Given the body of a view result or _changes feed (should be an InputStream),
   returns a lazy seq of each row of data therein.

   header? should be true
   when the result is expected to include additional information in an additional
   header line, e.g. total_rows, offset, etc. — in other words, header? should
   always be true, unless the response-body is from a continuous _changes feed
   (which only include data, no header info).  This header info is added as
   metadata to the returned lazy seq."
  [response-body header?]
  (let [lines (utils/read-lines response-body)
        [lines meta] (if header?
                        (response-with-header* lines)
                        (response-no-header* lines))]
    (with-meta (->> lines
                     (map (fn [^String line]
                            (response-handle-if-parsed* line)))
                     (remove nil?))
          ;; TODO why are we dissoc'ing :rows here?
          (dissoc meta :rows))))

(defn view-request
  "Accepts the same arguments as couchdb-request*, but processes the result assuming that the
   requested resource is a view (using lazy-view-seq)."
  [method url & opts]
  (if-let [response (apply couchdb-request method (assoc url :as :stream) opts)]
    (lazy-view-seq response true)
    (throw (java.io.IOException. (str "No such view: " url)))))
