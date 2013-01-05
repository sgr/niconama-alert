;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ログやエラー処理に関する操作"}
  log-utils
  (:use [prefs-utils :only [pref-base-path]]
        [clojure.tools.logging])
  (:import [java.io File FileInputStream FileOutputStream]
	   [java.util Date Properties]
	   [java.util.logging Formatter LogManager LogRecord]
	   [java.text SimpleDateFormat]))

(def ^{:private true} SEPARATOR (System/getProperty "line.separator"))

(gen-class
 :name utils.Log4JLikeFormatter
 :extends java.util.logging.Formatter
 :prefix "l4f-")

(defn- with-throwable [^String msg ^Throwable thrown]
  (loop [s msg t thrown]
    (let [ss (reduce #(str %1 %2)
		     (str s (format "Caused by %s: %s%s" (.getName (class t))
				    (if-let [m (.getMessage t)] m "") SEPARATOR))
		     (map #(format "        at %s%s" % SEPARATOR) (.getStackTrace t)))]
      (if-let [c (.getCause t)] (recur ss c) ss))))

(defn- l4f-format [this ^LogRecord r]
  (let [msg (format "%s %-7s %s [%s] %s%s"
		    (.format (SimpleDateFormat. "yyyy-MM-dd HH:mm:ss SSS") (Date. (.getMillis r)))
		    (.getName (.getLevel r))
		    (.getLoggerName r)
		    (.getThreadID r)
		    (.getMessage r)
		    SEPARATOR)]
    (if-let [e (.getThrown r)] (with-throwable msg e) msg)))

(defn- path-log-props [appname]
  (str (pref-base-path) "." appname "-log.properties"))

(defn- create-log-props [^Properties props ^File file]
  (when props
    (with-open [os (FileOutputStream. file)]
      (.store props os "This is an auto generated properties"))))

(defn load-log-props [^String appname ^Properties default-props]
  (let [file (File. (path-log-props appname))]
    (when-not (.exists file) (create-log-props default-props file))
    (try
      (with-open [is (FileInputStream. file)]
        (doto (LogManager/getLogManager) (.readConfiguration is))
        true)
      (catch Exception e
        (error e (format "failed reading log properties from %s" (.getCanonicalPath file)))
        false))))

(defmacro with-log
  {:arglists '([level msg & body] [level throwable msg & body])}
  [level x & more]
  (if (instance? Throwable x)
    `(do (log ~level ~x ~(first more)) ~@(rest more))
    `(do (log ~level nil ~x) ~@more)))

(defmacro with-trace [x & more] `(with-log :trace ~x ~@more))
(defmacro with-debug [x & more] `(with-log :debug ~x ~@more))
(defmacro with-info  [x & more] `(with-log :info  ~x ~@more))
(defmacro with-warn  [x & more] `(with-log :warn  ~x ~@more))
(defmacro with-error [x & more] `(with-log :error ~x ~@more))
(defmacro with-fatal [x & more] `(with-log :fatal ~x ~@more))

