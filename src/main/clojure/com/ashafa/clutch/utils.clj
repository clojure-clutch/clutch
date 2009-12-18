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

(ns com.ashafa.clutch.utils
  (:require [clojure.contrib.json.write :as json-write])
  (:import [java.net.URLEncoder]))


(defn uri-encode
  [string]
  (.. URLEncoder (encode string) (replace "+" "%20")))

(defn map-to-query-str
  [m]
  (let [kws (keys m)]
    (reduce 
     (fn [q kw]
       (let [k (if (keyword? kw) (name kw) kw)
             a (if (not (= (last kws) kw)) "&")]
         (str q (uri-encode k) "=" (uri-encode (json-write/json-str (m kw))) a)))
     "?" kws)))

(defn options-to-map 
  [init options]
  (if (nth options 0)
    (apply (partial assoc init) options) 
    init))

(defn get-database-url
  [db-meta]
  (str "http://"
       (if (:username db-meta)
         (str (:username db-meta) ":" (:password db-meta) "@"))
       (:host db-meta) ":" (:port db-meta) "/" (:name db-meta)))

(defn get-mime-type
  [file]
  (.getContentType
   (javax.activation.MimetypesFileTypeMap.) file))

(defn convert-input-to-bytes
  [input]
  (let [barr (make-array Byte/TYPE 1024)
        out  (java.io.ByteArrayOutputStream.)]
    (loop [r (.read input barr)]
      (if (> r 0)
        (do
          (.write out barr 0 r)
          (recur (.read input barr)))))
    (.toByteArray out)))

(defn encode-bytes-to-base64
  [bytes]
  (.replaceAll
   (.encode (sun.misc.BASE64Encoder.) bytes) "\n" ""))
    