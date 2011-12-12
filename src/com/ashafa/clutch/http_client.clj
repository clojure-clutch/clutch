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

(ns ^{:author "Tunde Ashafa"}
  com.ashafa.clutch.http-client
  (:require [clojure.data.json :as json]
            [clojure.contrib.io :as io]
            [com.ashafa.clutch.utils :as utils]) 
  (:import  (java.io IOException InputStream InputStreamReader PushbackReader)
            (java.net URL URLConnection HttpURLConnection MalformedURLException)
            (sun.misc BASE64Encoder)))


(def ^{:private true} version "0.3.0")
(def ^String *encoding* "UTF-8") ; are we ever reading anything other than UTF-8 from couch?
(def ^{:dynamic true} *default-data-type* "application/json")
(def ^{:dynamic true} *configuration-defaults* {:read-timeout 0
                                                :connect-timeout 5000
                                                :use-caches false
                                                :follow-redirects true
                                                :read-json-response true})

(def  ^{:doc "When thread-bound to any value, will be reset! to the HTTP response code of the last couchdb request."
        :dynamic true}
     *response-code* nil)


(defn- send-body
  [^URLConnection connection data]
  (with-open [output (.getOutputStream connection)]
    (io/copy data output)
    ; make sure streams are closed so we don't hold locks on files on Windows
    (when (instance? InputStream data) (.close ^InputStream data))))

(defn- get-response
  [^HttpURLConnection connection {:keys [read-json-response]}]
  (let [response-code (.getResponseCode connection)]
    (when (thread-bound? #'*response-code*) (set! *response-code* response-code))
    (cond (< response-code 400)
          (if read-json-response
            (with-open [input (.getInputStream connection)]
              (json/read-json (PushbackReader. (InputStreamReader. input *encoding*)) true))
            (.getInputStream connection))
          (= response-code 404) nil
          :else (throw
                 (IOException.
                  (str "CouchDB Response Error: " response-code " " (.getResponseMessage connection)))))))

(defn- connect
  [^com.ashafa.clutch.utils.URL {:as request
                                 :keys [method data]}]
  (let [^HttpURLConnection connection (.openConnection (URL. (str request)))
        configuration (merge *configuration-defaults* request)]
    ; can't just use .setRequestMethod because it throws an exception on
    ; any "illegal" [sic] HTTP methods, including couchdb's COPY
    (if (= "https" (:protocol request))
      (.setRequestMethod connection method)
      (utils/set-field HttpURLConnection :method connection method))
    (doto connection
      (.setUseCaches (configuration :use-caches))
      (.setConnectTimeout (configuration :connect-timeout))
      (.setReadTimeout (configuration :read-timeout))
      (.setInstanceFollowRedirects (configuration :follow-redirects)))
    (doseq [[k v] (:headers configuration)]
      (.setRequestProperty connection k v))
    (if data
      (do
        (.setDoOutput connection true)
        (send-body connection data))
      (.connect connection))
    (get-response connection configuration)))

(defn couchdb-request
  "Prepare request for CouchDB server by forming the required url, setting headers, and
   if required, the post/put body and its mime type."
  [method url & {:keys [data data-type headers]}]
  (let [raw-data  data
        data-type (or data-type *default-data-type*)       
        data      (if (map? raw-data) (json/json-str raw-data) raw-data)
        d-headers (merge {"Content-Type" data-type
                          "User-Agent" (str "com.ashafa.clutch.http-client/" version)
                          "Accept" "*/*"}
                    headers)
        d-headers (if (string? data)
                    (assoc d-headers "Content-Length" (-> data count str))
                    d-headers)
        headers   (if-let [creds (utils/url-creds url)]
                    (assoc d-headers
                      "Authorization"
                      (str "Basic " (.encode (BASE64Encoder.) (.getBytes creds))))
                    d-headers)]
    (connect (assoc url
                    :headers headers
                    :data data
                    :method (.toUpperCase (name method))))))
