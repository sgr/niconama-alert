;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "時間に関する操作"}
  time-utils
  (:import (java.text SimpleDateFormat)
	   (java.util Calendar Date)))

(defn now [] (.getTime (Calendar/getInstance)))
(defn format-time [date fmt]
  (if (instance? java.util.Date date)
    (.format (SimpleDateFormat. fmt) date)
    (.toString date)))
(defn format-time-long [date] (format-time date "yyyy/MM/dd HH:mm:ss"))
(defn format-time-short [date] (format-time date "MM/dd HH:mm"))

(defn earlier? [^Date this ^Date that] (neg? (.compareTo this that)))

(defn interval [^Date from ^Date to] (- (.getTime to) (.getTime from)))

(defn minute [millisec] (int (/ (/ millisec 1000) 60)))

