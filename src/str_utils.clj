;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "文字列に関する操作"}
  str-utils
  (:import (java.io ByteArrayInputStream)))

(defn cleanup
  "絵文字など制御文字扱いになる文字を削除する"
  [^String s]
  (if s (.replaceAll s "[\\00-\\x1f\\x7f]" "") s))

(defn utf8stream
  "translate from String to Stream."
  [^String s]
  (if s (ByteArrayInputStream. (.getBytes s "UTF-8")) nil))

(defn ifstr [s es] (if s s es))

(defn split-by-length
  "split string by given length"
  [^String s ^Integer n]
  (if (nil? s)
    [""]
    (let [l (.length s)]
      (loop [result [], i 0]
	(let [end (if (< (+ i n) l) (+ i n) l)]
	  (if (>= i l)
	    result
	    (recur (conj result (.substring s i end)) (+ i (- end i)))))))))
