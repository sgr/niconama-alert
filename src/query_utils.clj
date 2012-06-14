;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "クエリー操作"}
  query-utils
  (:require [clojure.string :as s])
  (:import [java.util Stack]))

(def ^{:private true} prefixes #{'not 'NOT})
(def ^{:private true} infixes #{'and 'or 'AND 'OR})

(defn- to-where-clause-aux [q column]
  (letfn [(like [word] (format "(%s LIKE '%%%s%%')" column word))
          (hoge [coll] (for [itm coll]
                         (if (list? itm)
                           (to-where-clause-aux itm column)
                           (like itm))))]
    (if-not (list? q)
      (like q)
      (if (> 2 (count q))
        (throw (IllegalArgumentException. (format "malformed query(%d): %s" (count q) (pr-str q))))
        (let [op (first q)]
          (cond (contains? prefixes op) (str (format "(%s " op) (s/join (hoge (rest q))) ")")
                (contains? infixes op) (str "(" (s/join (format " %s " op) (hoge (rest q))) ")") 
                :else (throw (IllegalArgumentException. (format "unsupported operator: %s" op)))))))))

(defn to-where-clause1
  [q columns]
  (cond (< 1 (count columns))
        (s/join " or " (for [c columns] (to-where-clause-aux q c)))
        (= 1 (count columns))
        (to-where-clause-aux q (first columns))
        :else (throw (IllegalArgumentException. (format "No columns was specified: %s" (pr-str columns))))))

(defn to-where-clause
  "prefix->infix for translating text matching query to SQL WHERE-CLAUSE using LIKE"
  [q-str columns]
  (letfn [(readq
	   [q]
	   (with-open [r (java.io.PushbackReader. (java.io.StringReader. q))]
	     (loop [o (read r nil :EOF), lst '()]
	       (if (= :EOF o) lst
		   (recur (read r nil :EOF) (conj lst o))))))]
    (let [q (readq q-str)]
      (cond (< 1 (count q))
            (to-where-clause1 (readq q-str) columns)
            (= 1 (count q))
            (to-where-clause1 (first (readq q-str)) columns)
            :else (throw (IllegalArgumentException. (format "malformed query: %s" q)))))))
