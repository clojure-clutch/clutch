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
            [clojure.contrib.json.write :as json-write]
            [clojure.contrib.duck-streams :as duck-streams :only [spit]]
            [com.ashafa.clutch.utils :as utils]) 
  (:import  (java.io IOException InputStreamReader PushbackReader FileInputStream)
            (java.net URL MalformedURLException)
            (sun.misc BASE64Encoder)))


(def *version* "0.0")
(def *encoding* "UTF-8")
(def *default-data-type* "application/json")
(def *configuration-defaults* {:read-timeout 10000
                               :connect-timeout 5000
                               :use-caches false
                               :follow-redirects false})
; @todo - we'll be able to eliminate the atom requirement when thread-bound? is available in core
;  http://www.assembla.com/spaces/clojure/tickets/243
(def #^{:doc "When bound to an atom, will be reset! to the HTTP response code of the last couchdb request."}
  *response-code* nil)

(defn- send-body
  [connection data]
  (with-open [output (.getOutputStream connection)]
    (if (string? data)
      (duck-streams/spit output data)
      (let [stream (FileInputStream. data)
            bytes  (make-array Byte/TYPE 1024)]
        (loop [bytes-read (.read stream bytes)]
          (when (pos? bytes-read)
            (.write output bytes 0 bytes-read)
            (recur (.read stream bytes))))))))

(defn- get-response
  [connection]
  (let [response-code (.getResponseCode connection)]
    (when *response-code* (reset! *response-code* response-code))
    (cond (< response-code 400)
          (with-open [input (.getInputStream connection)]
            (binding [json-read/*json-keyword-keys* true]
              (json-read/read-json (PushbackReader. (InputStreamReader. input *encoding*)))))
          (= response-code 404) nil
          :else (throw
                 (IOException.
                  (str "CouchDB Response Error: " response-code " " (.getResponseMessage connection)))))))

(defn- connect
  [url method headers data]
  (let [connection    (.openConnection (URL. url))
        configuration (merge *configuration-defaults* headers)]
    (doto connection
      (.setRequestMethod method)
      (.setUseCaches (configuration :use-caches))
      (.setConnectTimeout (configuration :connect-timeout))
      (.setReadTimeout (configuration :read-timeout))
      (.setInstanceFollowRedirects (configuration :follow-redirects)))
    (doseq [key-words (keys (configuration :headers))]
      (.setRequestProperty connection key-words ((configuration :headers) key-words)))
    (if data
      (do (.setDoOutput connection true) (send-body connection data))
      (.connect connection))    
    (get-response connection)))

(defn couchdb-request
  "Prepare request for CouchDB server by forming the required url, setting headers, and
   if required, the post/put body and its mime type."
  [config method & cmd-data-type]
  (let [command   (if (first cmd-data-type) (str "/" (first cmd-data-type)))
        raw-data  (nth cmd-data-type 1 nil)
        data-type (nth cmd-data-type 2 *default-data-type*)
        database  (if (config :name) (str "/" (config :name)))
        url       (str "http://" (config :host) ":" (config :port) 
                       (if (and database (re-find #"\?" database))
                         (.replace database "?" (str command "?"))
                         (str database command)))
        data      (if (map? raw-data) (json-write/json-str raw-data) raw-data)
        d-headers {"Content-Length" (str (cond (string? data) (count data)
                                               (instance? java.io.File data) (.length data)
                                               :else 0))
                   "Content-Type" data-type
                   "User-Agent" (str "clutch.http-client/" *version*)}
        headers   (if (:username config)
                    (assoc d-headers
                      "Authorization"
                      (str "Basic "
                           (.encode (BASE64Encoder.)
                                    (.getBytes (str (config :username) ":" (:password config))))))
                    d-headers)
        method    (.toUpperCase (if (keyword? method) (name method) method))]
    (println url)
    (connect url method {:headers headers} data)))