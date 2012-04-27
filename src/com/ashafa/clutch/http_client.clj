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
                                                :read-json-response true})

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
  (when (thread-bound? #'*response*) (set! *response* response)))

(defn- connect
  [request]
  (let [configuration (merge *configuration-defaults* request)
        data (:data request)]
    (try+
      (let [resp (http/request (merge {:method (:method request)
                                       :url (str request)
                                       :headers (:headers configuration)
                                       :follow-redirects (:follow-redirects configuration) 
                                       :socket-timeout (:socket-timeout configuration)
                                       :conn-timeout (:conn-timeout configuration)}
                                      (when data {:body data})
                                      (when (instance? InputStream data)
                                        {:length (:data-length request)})
                                      (if (:read-json-response configuration)
                                        {:as :json}
                                        {:as :stream})))]
        (set!-*response* resp)
        (:body resp))
      (catch identity ex
        (if (map? ex)
          (do
            (set!-*response* ex)
            (when-not (== 404 (:status ex))
              (throw+ ex)))
          (throw+ ex))))))

(defn couchdb-request
  "Prepare request for CouchDB server by forming the required url, setting headers, and
   if required, the post/put body and its mime type."
  [method url & {:keys [data data-length content-type headers]}]
  (connect (assoc url
             :method method
             :data (if (map? data) (json/generate-string data) data)
             :data-length data-length
             :headers (merge {"Content-Type" (or content-type *default-data-type*)
                              "User-Agent" user-agent
                              "Accept" "*/*"}
                             headers))))

(defn view-request
  "Accepts the same arguments as couchdb-request, but processes the result assuming that the
   requested resource is a view.  Returns a lazy sequence of the view result's :rows slot,
   with other values (:total_rows, :offset, etc) added as metadata to the lazy seq."
  [method url & args]
  (let [resp (fail-on-404 url (apply couchdb-request method (assoc url :read-json-response false) args)) 
        lines (utils/read-lines resp)
        meta (-> (first lines)
               (str/replace #",?\"rows\":\[\s*$" "}")  ; TODO this is going to break eventually :-/ 
               (json/parse-string true))]
    (with-meta (->> (rest lines)
                 (map (fn [^String line]
                        (when (.startsWith line "{")
                          (json/parse-string line true))))
                 (remove nil?))
      (dissoc meta :rows))))

