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
(defn timestamp-to-date [^Timestamp t]
  (.getTime (doto (Calendar/getInstance) (.setTimeInMillis (.getTime t)))))

(defn update-timestamp-sqlite
  "sqliteはtimestamp型をlongで保持しているので、ResultSetから取り出したマップをこの関数で変換する。
   TODO 今はad hocな対応。後でrow-to-pgmと合わせて整理する。"
  [raw-row ks]
  (reduce (fn [m [k v]] (if (some #(= k %) ks)
                          (assoc m k (Timestamp. v))
                          (assoc m k v)))
          {} raw-row))

(defn format-time [date fmt]
  (if (instance? java.util.Date date)
    (.format (SimpleDateFormat. fmt) date)
    date))
(defn format-time-long [date] (format-time date "yyyy/MM/dd HH:mm:ss"))
(defn format-time-short [date] (format-time date "MM/dd HH:mm"))

(defn later? [^Date this ^Date that] (pos? (.compareTo this that)))
(defn earlier? [^Date this ^Date that] (neg? (.compareTo this that)))

(defn interval [^Date from ^Date to] (- (.getTime to) (.getTime from)))

(defn minute [millisec] (int (/ (/ millisec 1000) 60)))

(defn within? [^Date from ^Date to ^Integer sec]
  (if (> (* sec 1000) (math/abs (- (.getTime to) (.getTime from)))) true false))
