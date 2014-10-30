;; -*- coding: utf-8-unix -*-
(ns nico.string
  (:require [clojure.string :as s])
  (:import [java.io ByteArrayInputStream]
           [org.apache.commons.lang3 StringEscapeUtils]))

(defn nstr
  "新しいStringを返す。nilの場合は長さ0の文字列を返す。
   これはJava version 7u6で修正されたStringのメモリリークバグ対策関数である。MacのOSバンドルJavaは6のため必要である。
   詳しくはこちらを参照。 http://bugs.java.com/bugdatabase/view_bug.do?bug_id=4513622"
  [^String str]
  (if (nil? str) "" (String. str)))

(defn unescape
  [^String str type]
  (if-not (s/blank? str)
    (condp = type
      :html (StringEscapeUtils/unescapeHtml4 str)
      :xml  (StringEscapeUtils/unescapeXml str)
      str)
    ""))

(defn utf8stream
  "translate from String to Stream."
  [^String s]
  (when s (ByteArrayInputStream. (.getBytes s "UTF-8"))))

