;; -*- coding: utf-8-unix -*-
(ns nico.string
  (:import [java.io ByteArrayInputStream]
           [java.util.regex Pattern]
           [org.apache.commons.lang3 StringEscapeUtils]))

(let [p (Pattern/compile "\\p{Cntrl}")
      m (.matcher p "a")]
  (defn cleanup
    "絵文字など制御文字扱いになる文字を削除する"
    [^String s]
    (locking m
      (.reset m s)
      (.replaceAll m ""))))

(defn unescape
  [^String s type]
  (when s
    (condp = type
      :html (StringEscapeUtils/unescapeHtml4 s)
      :xml  (StringEscapeUtils/unescapeXml   s)
      s)))

(defn utf8stream
  "translate from String to Stream."
  [^String s]
  (when s (ByteArrayInputStream. (.getBytes s "UTF-8"))))

