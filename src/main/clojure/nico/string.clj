;; -*- coding: utf-8-unix -*-
(ns nico.string
  (:import [java.io ByteArrayInputStream]
           [java.util.regex Pattern]
           [org.apache.commons.lang3 StringEscapeUtils]))

(defn nstr
  "新しいStringを返す。nilの場合は長さ0の文字列を返す。
   これはJava version 7u6で修正されたStringのメモリリークバグ対策関数である。MacのOSバンドルJavaは6のため必要である。
   詳しくはこちらを参照。 http://bugs.java.com/bugdatabase/view_bug.do?bug_id=4513622"
  [^String s]
  (if (nil? s) "" (String. s)))

(let [p (Pattern/compile "\\p{Cntrl}")]
  (defn cleanup
    "絵文字など制御文字扱いになる文字を削除する"
    [^String s]
    (-> (.matcher p s) (.replaceAll ""))))

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

