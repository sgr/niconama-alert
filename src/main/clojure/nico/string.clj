;; -*- coding: utf-8-unix -*-
(ns nico.string
  (:require [clojure.string :as s])
            ;;[clojure.tools.logging :as log])
  (:import [java.io ByteArrayInputStream InputStream InputStreamReader]
           [org.apache.commons.lang3 StringEscapeUtils]))

(defn nstr
  "新しいStringを返す。nilの場合は長さ0の文字列を返す。
   これはJava version 7u6で修正されたStringのメモリリークバグ対策関数である。MacのOSバンドルJavaは6のため必要である。
   詳しくはこちらを参照。 http://bugs.java.com/bugdatabase/view_bug.do?bug_id=4513622"
  [^String str]
  (if (nil? str) "" (String. str)))

(defn non-printable-char? [c]
  (let [i (int c)]
    ;; 9 (Horizontal Tab), 10 (Line feed), 13 (Carriage return) は目こぼしする
    (or (<= 0 i 8) (= 11 i) (= 12 i) (<= 14 i 31) (= 127 i))))

(defn ^InputStreamReader clean-reader
  "XMLパーサーが受理しない文字を削除するInputStreamReaderを返す"
  [^InputStream is]
  (proxy [InputStreamReader] [is "UTF-8"]
    (read
      ([]
         (loop [c (proxy-super read)]
           (if (non-printable-char? c)
             (recur (proxy-super read))
             c)))
      ([cbuf offset len]
         (let [tbuf (char-array len)
               l (proxy-super read tbuf 0 len)]
           (if (pos? l)
             (let [buf (->> tbuf (take l) (filter #(not (non-printable-char? %))) (map char))
                   nl (count buf)]
               ;; (when (not= nl l)
               ;;   (let [ccs (->> tbuf (take l) (filter non-printable-char?) (map #(Integer/toHexString (int %))))]
               ;;     (log/debugf "DETECTED NON PRINTABLE CHAR(S) (%d, %d, %s)" l nl (pr-str ccs))))
               (doall (map-indexed (fn [i c] (aset-char cbuf (+ offset i) c)) buf))
               nl)
             l))))))

(defn del-dup
  "連続した空白や改行を一つにまとめる"
  [^String str]
  (-> str
      (s/replace #"(\s)+" " ")
      (s/replace #"(\　)+" "　")
      (s/replace #"(\n)+" "\n")))

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

