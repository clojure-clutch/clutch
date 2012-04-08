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

(ns ^{:author "Tunde Ashafa"}
  com.ashafa.clutch.utils
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [cemerick.url :as url])
  (:use clojure.contrib.core)
  (:import java.net.URLEncoder
           java.lang.Class
           [java.io File InputStream ByteArrayOutputStream]))

(defn url
  "Thin layer on top of cemerick.url/url that defaults otherwise unqualified
   database urls to use `http://localhost:5984`."
  [& [base & parts :as args]]
  (try
    (apply url/url base (map url/url-encode parts))
    (catch java.net.MalformedURLException e
      (apply url/url "http://localhost:5984" (map url/url-encode args)))))

(defn server-url
  [db]
  (assoc db :path nil :query nil))

(defn get-mime-type
  [^File file]
  (.getContentType
   (javax.activation.MimetypesFileTypeMap.) file))

(defn to-byte-array
  [^InputStream input]
  (let [barr (make-array Byte/TYPE 1024)
        out (ByteArrayOutputStream.)]
    (loop []
      (let [size (.read input barr)]
        (when (pos? size)
          (do (.write out barr 0 size)
            (recur)))))
    (.toByteArray out)))

;; TODO use commons-codec, or data.codec in new contrib 
(defn encode-bytes-to-base64
  [^bytes bytes]
  (.replaceAll
   (.encode (sun.misc.BASE64Encoder.) bytes) "\n" ""))

;; TODO eliminate when sane http client is integrated
(defn set-field
  "Set to private or protected field. field-name is a symbol or keyword.
   This will presumably be added to clojure.contrib.reflect eventually...?"
  [^Class klass field-name obj value]
  (-> klass
    (.getDeclaredField (name field-name))
    (doto (.setAccessible true))
    (.set obj value)))

;; TODO should be replaced with a java.io.Closeable Seq implementation and used
;; in conjunction with with-open on the client side
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


