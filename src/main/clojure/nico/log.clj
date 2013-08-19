;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "アプリケーションログに関する処理"}
  nico.log
  (:use [clojure.tools.logging])
  (:require [nico.prefs :as prefs])
  (:import [java.io File FileInputStream FileOutputStream IOException]
           [java.util Properties]
	   [java.util.logging Level Logger LogManager]))

(defn- default-log-props []
  (doto (Properties.)
    (.setProperty "handlers" "java.util.logging.FileHandler")
    (.setProperty "java.util.logging.FileHandler.pattern"
                  (str (clojure.string/replace (.getPath (prefs/pref-dir)) #"\\" "/") ; Java SE7ではnio.fileでもっと綺麗に書けるはず
                       "/nico_%g.log"))
    (.setProperty "java.util.logging.FileHandler.limit" "10485760")
    (.setProperty "java.util.logging.FileHandler.count" "10")
    (.setProperty "java.util.logging.FileHandler.formatter" "logutil.Log4JLikeFormatter")
    (.setProperty "java.util.logging.FileHandler.encoding" "utf-8")
    (.setProperty ".level" "INFO")
    (.setProperty "nico.level" "INFO")))

(defn- create-log-props [^Properties props ^File file]
  (when props
    (with-open [os (FileOutputStream. file)]
      (.store props os "This is an auto generated properties"))))

(defn- load-log-props-aux [^Properties default-props]
  (let [file (File. ^File (prefs/pref-dir) "logging.properties")]
    (when-not (.exists file) (create-log-props default-props file))
    (with-open [is (FileInputStream. file)]
      (doto (LogManager/getLogManager) (.readConfiguration is)))))

(defn load-log-props []
  (load-log-props-aux (default-log-props))
  (doto (Logger/getLogger "sun.awt") (.setLevel Level/OFF))
  (doto (Logger/getLogger "java.awt") (.setLevel Level/OFF))
  (doto (Logger/getLogger "javax.swing") (.setLevel Level/OFF)))

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
