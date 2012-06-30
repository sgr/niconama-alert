;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "IO Utility."}
  io-utils
  (:use [clojure.java.io :only [file delete-file]]
        [clojure.tools.logging])
  (:require [log-utils :as l])
  (:import [java.io ByteArrayOutputStream File]))

(defn- byte-buf [len] (byte-array len (repeat len (byte 0))))

(defn input-stream-to-bytes [is]
  (let [buf-size 1024
        buf (byte-buf buf-size)
        bos (ByteArrayOutputStream.)]
    (loop [n (.read is buf 0 buf-size)]
      (if (= -1 n)
        (do (.flush bos)
            (.toByteArray bos))
        (do (.write bos buf 0 n)
            (recur (.read is buf 0 buf-size)))))))

(defn delete-all-files
  "If the path describes a file, it will be deleted only.
   If the path describes a directory, both itself and containing files will be deleted recursively."
  [path]
  (let [f (file path)]
    (when (.exists f)
      (if (.isDirectory f)
        (l/with-debug (format "deleting all children of %s"  path)
          (doseq [c (.listFiles f)] (delete-all-files c))
          (let [result (.delete f)]
            (debug (format "deleting directory: %s -> %s" path result))))
        (let [result (.delete f)]
          (debug (format "deleting file: %s -> %s" path result)))))))
