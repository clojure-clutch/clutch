;; Copyright (c) 2009-2010 Tunde Ashafa
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
  com.ashafa.clutch.utils
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:use clojure.contrib.core)
  (:import java.net.URLEncoder
           java.lang.Class
           [java.io File InputStream]))

(defn uri-encode
  [string]
  (-?> string (URLEncoder/encode "UTF-8") (.replace "+" "%20")))

(defn map-to-query-str
  ([m]
    (map-to-query-str m (constantly true)))
  ([m params-not-encoded-fn?]
    (-?>> (seq m)
          sort                     ; sorting makes testing a lot easier :-)
          (map (fn [[k v]]
                 [(uri-encode (if (keyword? k) (name k) k))
                  "="
                  (uri-encode (if (params-not-encoded-fn? k)
                                (json/json-str v)
                                (str v)))]))
          (interpose "&")
          flatten
          (apply str "?"))))

(defn options-to-map 
  [init options]
  (if (nth options 0)
    (apply (partial assoc init) options) 
    init))

(defn set-field
  "Set to private or protected field. field-name is a symbol or keyword.
   This will presumably be added to clojure.contrib.reflect eventually...?"
  [^Class klass field-name obj value]
  (-> klass
    (.getDeclaredField (name field-name))
    (doto (.setAccessible true))
    (.set obj value)))

(defn db-meta->url
  [db-meta]
  (str "http://"
       (if (:username db-meta)
         (str (:username db-meta) ":" (:password db-meta) "@"))
       (:host db-meta) ":" (:port db-meta) "/" (:name db-meta)))

(defn url->db-meta
  "Given a url, returns a map with slots aligned with *defaults*.
   Supports usage of URLs with with-db, etc."
  [url]
  (let [java-url      (java.net.URL. url)
        userinfo      (.getUserInfo java-url)
        [m user pass] (if userinfo (re-matches #"([^:]+):(.*$)" userinfo))
        url-port      (.getPort java-url)
        port          (if (> url-port -1) url-port 5984)]
    {:host     (.getHost java-url)
     :port     port
     :username user
     :password pass
     :name     (.getPath java-url)}))

(defn get-mime-type
  [^File file]
  (.getContentType
   (javax.activation.MimetypesFileTypeMap.) file))

(defn convert-input-to-bytes
  [^InputStream input]
  (let [barr (make-array Byte/TYPE 1024)
        out  (java.io.ByteArrayOutputStream.)]
    (loop [r (.read input barr)]
      (if (> r 0)
        (do
          (.write out barr 0 r)
          (recur (.read input barr)))))
    (.toByteArray out)))

(defn encode-bytes-to-base64
  [^bytes bytes]
  (.replaceAll
   (.encode (sun.misc.BASE64Encoder.) bytes) "\n" ""))

(defn read-lines
  "Like clojure.core/line-seq but opens f with reader.  Automatically
  closes the reader AFTER YOU CONSUME THE ENTIRE SEQUENCE.

  Pulled from clojure.contrib.io so as to avoid dependency on the old io
  namespace."
  [f]
  (let [read-line (fn this [^java.io.BufferedReader rdr]
                    (lazy-seq
                     (if-let [line (.readLine rdr)]
                       (cons line (this rdr))
                       (.close rdr))))]
    (read-line (io/reader f))))
