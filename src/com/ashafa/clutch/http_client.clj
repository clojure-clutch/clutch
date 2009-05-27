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

(ns com.ashafa.clutch.http-client
  (:require [clojure.contrib.json.read :as json-read]
            [clojure.contrib.json.write :as json-write])
  (:import  (java.io Reader InputStreamReader PushbackReader
                     OutputStreamWriter)
            (java.net URL MalformedURLException)
            (sun.misc BASE64Encoder)))


(def *version* "0.0")
(def *encoding* "UTF-8")
(def *config-defaults* {:read-timeout 10000
                        :connect-timeout 5000
                        :use-caches true
                        :follow-redirects false})

(defn- response [status-code message stream]
  "Returns CouchDB response (JSON) as a clojure map if the status
   code returned indicates a successful request, else throw an 
   exception." 
  (cond (< status-code 400)
        (with-open [reader (PushbackReader. (InputStreamReader. stream *encoding*))]
          (binding [json-read/*json-keyword-keys* true]
            (json-read/read-json reader)))
        (= status-code 404) nil
        :else (throw
               (Exception.
                (str "CouchDB Response Error: " status-code " " message)))))

(defn- connect [url method headers json-data]
  "Sets the response headers and creates a URL connection to server."
 (try
   (let [conn (.openConnection (URL. url))
         conf (merge *config-defaults* headers)]
     (doto conn
       (.setRequestMethod method)
       (.setUseCaches (conf :use-caches))
       (.setConnectTimeout (conf :connect-timeout))
       (.setReadTimeout (conf :read-timeout))
       (.setInstanceFollowRedirects (conf :follow-redirects)))
     (doseq [kw (keys (conf :headers))]
       (.setRequestProperty conn kw ((conf :headers) kw)))
     (when (and json-data (or (= method "POST") (= method "PUT")))
       (.setDoOutput conn true)
       (with-open [out (OutputStreamWriter. (.getOutputStream conn) *encoding*)]
         (doto out
           (.write json-data)
           (.flush))))
     (let [code    (.getResponseCode conn)
           message (.getResponseMessage conn)]
       (try
        (response code message (.getInputStream conn))
        (catch Exception e
          (response code message nil))
        (finally
         (.disconnect conn)))))
   (catch MalformedURLException e
     (throw (IllegalArgumentException. "A valid URL is required.")))))
 
(defn couchdb-request [config method & cmd-data]
  "Prepare request for CouchDB server by forming the required url, 
   setting the headers, and if required post data."
  (let [command   (if (first cmd-data) (str "/" (first cmd-data)))
        data      (if (> (count cmd-data) 1) (second cmd-data))
        database  (if (config :name) (str "/" (config :name)))
        url       (str "http://" (config :host) ":" (config :port) database command)
        json-data (if data (json-write/json-str data))
        headers   {"Content-Length" (str (count json-data))
                   "Content-Type" "application/json"
                   "User-Agent" (str "clutch.http-client/" *version*)}
        headers   (if (:username config)
                    (assoc headers
                      "Authorization"
                      (str "Basic "
                           (.encode (BASE64Encoder.)
                                    (.getBytes (str (config :username) ":" (:password config))))))
                    headers)
        method    (.toUpperCase (if (keyword? method) (name method) method))]
    (connect url method {:headers headers} json-data)))