;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "設定ファイルの読み込み、書き出しインタフェース"}
  prefs-utils
  (:use [clojure.contrib.io :only [with-out-writer]])
  (:import (java.io File)))

(defn- pref-dir-unix [uhome]
  (str uhome File/separator))
(defn- pref-dir-macosx [uhome]
  (str uhome File/separator "Library/Application Support/"))
(defn- pref-dir-windows [uhome]
  (if-let [appdata (System/getenv "AppData")]
    (str appdata File/separator)
    (str uhome File/separator)))

(defn pref-base-path []
  (let [uhome (System/getProperty "user.home")
	sys (.toLowerCase (System/getProperty "os.name"))]
    (str (condp re-find sys
	   #"windows" (pref-dir-windows uhome)
	   #"mac" (pref-dir-macosx uhome)
	   #"linux" (pref-dir-unix uhome)
	   #"bsd" (pref-dir-unix uhome)
	   #"solaris" (pref-dir-unix uhome)
	   (pref-dir-unix uhome)))))

(defn- pref-path [appname]
  (str (pref-base-path) "." appname ".clj"))

(defn- old-pref-path [appname] (str (pref-path appname) ".old"))

(defn- pref-file [appname] (File. (pref-path appname)))
(defn- old-pref-file [appname] (File. (old-pref-path appname)))

(defn load-pref [appname]
  (let [pfile (pref-file appname)]
    (if (and (.exists pfile) (.isFile pfile))
      (read-string (slurp pfile))
      nil)))

(defn store-pref [pref appname]
  (let [pfile (pref-file appname)]
    (when (and (.exists pfile) (.isFile pfile))
      (.renameTo pfile (old-pref-file appname)))
    (with-out-writer pfile (prn pref))))
