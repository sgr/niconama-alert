;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "アプリケーションログに関する処理"}
  nico.log
  (:require [log-utils :as lu])
  (:import (java.util Properties)
	   (java.util.logging Level Logger)))

(defn- default-log-props []
  (doto (Properties.)
    (.setProperty "handlers" "java.util.logging.FileHandler")
    (.setProperty "java.util.logging.FileHandler.pattern" "%t/nico_%g.log")
    (.setProperty "java.util.logging.FileHandler.limit" "10485760")
    (.setProperty "java.util.logging.FileHandler.count" "10")
    (.setProperty "java.util.logging.FileHandler.formatter" "utils.Log4JLikeFormatter")
    (.setProperty "java.util.logging.FileHandler.encoding" "utf-8")
    (.setProperty ".level" "INFO")))

(defn load-log-props []
  (lu/load-log-props "nico" (default-log-props))
  (doto (Logger/getLogger "sun.awt") (.setLevel Level/OFF))
  (doto (Logger/getLogger "javax.swing") (.setLevel Level/OFF))
  (doto (Logger/getLogger "java.awt") (.setLevel Level/OFF)))


