;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "アプリケーションログに関する処理"}
  nico.log
  (:require [log-utils :as lu])
  (:import (java.util Properties)))

(defn- default-log-props []
  (doto (Properties.)
    (.setProperty "handlers" "java.util.logging.FileHandler")
    (.setProperty "java.util.logging.FileHandler.pattern" "%t/nico_%g.log")
    (.setProperty "java.util.logging.FileHandler.limit" "10485760")
    (.setProperty "java.util.logging.FileHandler.count" "10")
    (.setProperty "java.util.logging.FileHandler.formatter" "utils.Log4JLikeFormatter")
    (.setProperty ".level" "INFO")))

(defn load-log-props []
  (lu/load-log-props "nico" (default-log-props)))

