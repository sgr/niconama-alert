;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "文字列に関する操作"}
  str-utils
  (:use [clojure.contrib.logging])
  (:require [clojure.string :as s])
  (:import (java.io ByteArrayInputStream)))

(defn cleanup
  "絵文字など制御文字扱いになる文字を削除する"
  [^String s]
  (if s (.replaceAll s "[\\00-\\x1f\\x7f]" "") s))

(defn utf8stream
  "translate from String to Stream."
  [^String s]
  (if s (ByteArrayInputStream. (.getBytes s "UTF-8")) nil))

(defn ifstr [s es] (if s s es))

(defn split-by-length
  "split string by given length"
  [^String s ^Integer n]
  (if (nil? s)
    [""]
    (let [l (.length s)]
      (loop [result [], i 0]
	(let [end (if (< (+ i n) l) (+ i n) l)]
	  (if (>= i l)
	    result
	    (recur (conj result (.substring s i end)) (+ i (- end i)))))))))

(defn space? [^Char c] (some #(= % c) '(\space \tab)))
(defn quote? [^Char c] (some #(= % c) '(\" \')))
(defn escape? [^Char c] (= \\ c))

(declare in-word in-space in-quote)

(defn- in-word [src token dst]
  (trace (format "[W] %s, %s, %s" src token dst))
  (cond (= 0 (count src)) (if (< 0 (count token)) (conj dst (s/join token)) dst)
	(space? (first src)) #(in-space (rest src) [] (conj dst (s/join token)))
	(quote? (first src)) #(in-quote (rest src) [] (conj dst (s/join token)))
	:else                #(in-word  (rest src) (conj token (first src)) dst)))

(defn- in-space [src token dst]
  (trace (format "[S] %s, %s, %s" src token dst))
  (cond (= 0 (count src)) (if (< 0 (count token)) (conj dst (s/join token)) dst)
	(space? (first src)) #(in-space (rest src) [] dst)
	(quote? (first src)) #(in-quote (rest src) [] dst)
	:else                #(in-word  (rest src) [(first src)] dst)))

(defn- in-quote [src token dst]
  (trace (format "[Q] %s, %s, %s" src token dst))
  (cond (= 0 (count src)) (if (< 0 (count token)) (conj dst (s/join token)) dst)
	(quote? (first src))  #(in-space (rest src) [] (conj dst (s/join token)))
	(escape? (first src)) (let [rsrc (rest src)]
				#(in-quote (rest rsrc) (conj token (first rsrc)) dst))
	:else                 #(in-quote (rest src) (conj token (first src)) dst)))

(defn tokenize [^String s] (trampoline (in-space (char-array s) [] [])))
