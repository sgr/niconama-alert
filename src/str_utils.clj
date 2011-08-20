;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "文字列に関する操作"}
  str-utils
  (:import (java.io ByteArrayInputStream)))

(defn cleanup
  "絵文字など制御文字扱いになる文字を削除する"
  [^String s]
  (.replaceAll s "[\\00-\\x1f\\x7f]" ""))

(defn utf8stream
  "translate from String to Stream."
  [^String s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))
