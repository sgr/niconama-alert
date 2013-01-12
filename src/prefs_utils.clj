;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "設定ファイルの読み込み、書き出しインタフェース"}
  prefs-utils
  (:use [clojure.java.io :only [writer]])
  (:import [java.io File]))

(defmacro with-out-writer
  "Opens a writer on f, binds it to *out*, and evalutes body.
  Anything printed within body will be written to f.
  imported this macro from old clojure.contrib.io"
  [f & body]
  `(with-open [stream# (writer ~f)]
     (binding [*out* stream#]
       ~@body)))

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

(defn- ^String pref-path [appname]
  (str (pref-base-path) "." appname ".clj"))

(defn- ^String old-pref-path [appname] (str (pref-path appname) ".old"))

(defn- ^File pref-file [appname] (File. (pref-path appname)))
(defn- ^File old-pref-file [appname] (File. (old-pref-path appname)))

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
