;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "設定ファイルの読み込み、書き出しインタフェース"}
  prefs-utils
  (:use [clojure.java.io :only [writer]])
  (:import [java.io File]))

(defn system []
  (let [sys (.toLowerCase (System/getProperty "os.name"))]
    (condp re-find sys
      #"windows" :windows
      #"mac"     :mac
      #"linux"   :linux
      #"bsd"     :bsd
      #"solaris" :solaris
      :other)))

(defn- pref-base-path-unix [uhome]
  (str uhome File/separator))

(defn- pref-base-path-macosx [uhome]
  (str uhome File/separator "Library"))

(defn- pref-base-path-windows [uhome]
  (if-let [appdata (System/getenv "AppData")]
    (str appdata File/separator)
    (str uhome File/separator)))

(defn ^String pref-base-path []
  (let [uhome (System/getProperty "user.home")]
    (condp = (system)
      :windows (pref-base-path-windows uhome)
      :mac     (pref-base-path-macosx uhome)
      :linux   (pref-base-path-unix uhome)
      :bsd     (pref-base-path-unix uhome)
      :solaris (pref-base-path-unix uhome)
      (pref-base-path-unix uhome))))

(defn load-pref [^File pfile]
  (if (and (.exists pfile) (.isFile pfile))
    (read-string (slurp pfile))
    nil))

(defn- ^File old-pref-file [^File pfile] (File. (str (.getCanonicalPath pfile) ".old")))

(defmacro with-out-writer
  "Opens a writer on f, binds it to *out*, and evalutes body.
  Anything printed within body will be written to f.
  imported this macro from old clojure.contrib.io"
  [f & body]
  `(with-open [stream# (writer ~f)]
     (binding [*out* stream#]
       ~@body)))

(defn store-pref [pref ^File pfile]
  (when (and (.exists pfile) (.isFile pfile))
    (.renameTo pfile (old-pref-file pfile)))
  (with-out-writer pfile (prn pref)))
