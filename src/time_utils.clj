;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "時間に関する操作"}
  time-utils
  (:require [clojure.math.numeric-tower :as math])
  (:import [java.text SimpleDateFormat]
           [java.sql Timestamp]
	   [java.util Calendar Date]))

(defn now [] (.getTime (Calendar/getInstance)))
(defn date-to-timestamp [^Date d] (Timestamp. (.getTime d)))
(defn sql-now [] (date-to-timestamp (now)))

(let [fmt (SimpleDateFormat. "yyyy/MM/dd HH:mm:ss")]
  (defn format-time-long [^Date d] (.format fmt d)))

(let [fmt (SimpleDateFormat. "MM/dd HH:mm")]
  (defn format-time-short [^Date d] (.format fmt d)))

(defn later? [^Date this ^Date that] (pos? (.compareTo this that)))
(defn earlier? [^Date this ^Date that] (neg? (.compareTo this that)))

(defn interval [^Date from ^Date to] (- (.getTime to) (.getTime from)))

(defn minute [millisec] (int (/ (/ millisec 1000) 60)))

(defn within? [^Date from ^Date to ^Integer sec]
  (if (> (* sec 1000) (math/abs (- (.getTime to) (.getTime from)))) true false))
