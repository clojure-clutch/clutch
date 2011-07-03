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

(ns #^{:author "Tunde Ashafa"}
  com.ashafa.clutch.http-client
  (:require [clojure.data.json :as json]
            [clojure.contrib.io :as io]
            [com.ashafa.clutch.utils :as utils]) 
  (:import  (java.io IOException InputStream InputStreamReader PushbackReader)
            (java.net URL URLConnection HttpURLConnection MalformedURLException)
            (sun.misc BASE64Encoder)))


(def ^:private version "0.0")
(def ^String *encoding* "UTF-8") ; are we ever reading anything other than UTF-8 from couch?
(def ^:dynamic *default-data-type* "application/json")
(def ^:dynamic *configuration-defaults* {:read-timeout 0
                               :connect-timeout 5000
                               :use-caches false
                               :follow-redirects true
                               :read-json-response true})

; @todo - we'll be able to eliminate the atom requirement when thread-bound? is available in core
; http://www.assembla.com/spaces/clojure/tickets/243
(def #^{:doc "When bound to an atom, will be reset! to the HTTP response code of the last couchdb request."
        :dynamic true}
     *response-code* nil)


(defn- send-body
  [^URLConnection connection data]
  (with-open [output (.getOutputStream connection)]
    (io/copy data output)
    (if (instance? InputStream data) (.close ^InputStream data))))

(defn- get-response
  [^HttpURLConnection connection {:keys [read-json-response] :as config}]
  (let [response-code (.getResponseCode connection)]
    (when *response-code* (reset! *response-code* response-code))
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
  [url method config data]
  (let [^HttpURLConnection connection    (.openConnection (URL. url))
        configuration (merge *configuration-defaults* config)]    
    ; can't just use .setRequestMethod because it throws an exception on
    ; any "illegal" [sic] HTTP methods, including couchdb's COPY
    (utils/set-field HttpURLConnection :method connection method)
    (doto connection
      (.setUseCaches (configuration :use-caches))
      (.setConnectTimeout (configuration :connect-timeout))
      (.setReadTimeout (configuration :read-timeout))
      (.setInstanceFollowRedirects (configuration :follow-redirects)))
    (doseq [key-words (keys (configuration :headers))]
      (.setRequestProperty connection key-words ((configuration :headers) key-words)))
    (if data
      (do
        (.setDoOutput connection true)
        (send-body connection data))
      (.connect connection))
    (get-response connection configuration)))

(defn couchdb-request
  "Prepare request for CouchDB server by forming the required url, setting headers, and
   if required, the post/put body and its mime type."
  [config method & {:keys [command data data-type headers]}]
  (let [command   (when command (str "/" command))
        raw-data  data
        data-type (or data-type *default-data-type*)
        database  (if (config :name) (str "/" (config :name)))
        url       (str "http"
                       (if (config :ssl) "s") "://" (config :host)
                       ":"
                       (if (config :ssl) (config :ssl-port) (config :port))
                       (if (and database (re-find #"\?" database))
                         (.replace database "?" (str command "?"))
                         (str database command)))
        data      (if (map? raw-data) (json/json-str raw-data) raw-data)
        d-headers (merge {"Content-Type" data-type
                          "User-Agent" (str "com.ashafa.clutch.http-client/" version)
                          "Accept" "*/*"}
                    headers)
        d-headers (if (string? data)
                    (assoc d-headers "Content-Length" (-> data count str))
                    d-headers)
        headers   (if (:username config)
                    (assoc d-headers
                      "Authorization"
                      (str "Basic "
                           (.encode (BASE64Encoder.)
                                    (.getBytes (str (config :username) ":" (:password config))))))
                    d-headers)
        method    (.toUpperCase (name method))]
    (connect url method (assoc config :headers headers) data)))