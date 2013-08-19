;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "文字列に関する操作"}
  str-utils
  (:require [clojure.string :as s])
  (:import [java.io ByteArrayInputStream]
           [java.util.regex Pattern]
           [org.apache.commons.lang3 StringEscapeUtils]
           [org.htmlcleaner CompactXmlSerializer HtmlCleaner TagNode]))

(defn longer [^String x ^String y]
  (cond (and x y) (if (> (.length x) (.length y)) x y)
        (nil? x) y
        (nil? y) x
        :else nil))

(defn trim-to [^String s ^Integer max]
  (if (and s (< max (.length s))) (.substring s 0 max) s))

(let [p (Pattern/compile "\\p{Cntrl}")]
  (defn cleanup
    "絵文字など制御文字扱いになる文字を削除する"
    [^String s]
    (when s
      (-> p (.matcher s) (.replaceAll "")))))

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

(defn ifstr [s es] (if s s es))

(defn remove-tag [^String s]
  (let [^TagNode node (.clean (HtmlCleaner.) s)]
    (clojure.string/join (map #(-> ^TagNode % .getText .toString) (.getAllElements node true)))))

(defn split-by-length
  "split string by given length"
  [^String s ^long n]
  (if (nil? s)
    [""]
    (let [l (.length s)]
      (loop [result [], i 0]
        (let [end (min (+ i n) l)]
          (if (>= i l)
            result
            (recur (conj result (.substring s i end)) (+ i (- end i)))))))))
