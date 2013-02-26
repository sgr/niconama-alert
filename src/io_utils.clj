;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "IO Utility."}
  io-utils
  (:use [clojure.java.io :only [file delete-file]]
        [clojure.tools.logging])
  (:import [java.io ByteArrayOutputStream File IOException]))

(defn- byte-buf [len] (byte-array len (repeat len (byte 0))))

(defn input-stream-to-bytes [^java.io.InputStream is]
  (let [buf-size 1024
        buf (byte-buf buf-size)
        bos (ByteArrayOutputStream.)]
    (loop [n (.read is buf 0 buf-size)]
      (if (= -1 n)
        (do (.flush bos)
            (.toByteArray bos))
        (do (.write bos buf 0 n)
            (recur (.read is buf 0 buf-size)))))))

(defn temp-file-name
  ([^String prefix ^String suffix ^File dir]
     (let [f (File/createTempFile prefix suffix dir)]
       (.delete f)
       (.getCanonicalPath f)))
  ([^String prefix ^String suffix]
     (let [f (File/createTempFile prefix suffix)]
       (.delete f)
       (.getCanonicalPath f)))
  ([^String prefix] (temp-file-name prefix nil)))

(defn- delete [^File f]
  (if (.delete f)
    (trace (format "deleted %s" (.getCanonicalPath f)))
    (throw (IOException. (format "failed deleting %s" (.getCanonicalPath f))))))

(defn delete-all-files
  "If the path describes a file, it will be deleted only.
   If the path describes a directory, both itself and containing files will be deleted recursively."
  [path]
  (letfn [(delete-all-files-aux [^File f]
            (when (.exists f)
              (when (.isDirectory f)
                (debug (format "deleting all children of %s"  path))
                (doseq [c (.listFiles f)] (delete-all-files-aux c)))
              (delete f)))]
    (let [f (file path)]
      (try
        (delete-all-files-aux f)
        true
        (catch Exception e
          (error e (format "failed deleting file(s): %s" (.getCanonicalPath f)))
          false)))))
