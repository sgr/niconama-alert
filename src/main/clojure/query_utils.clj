;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "クエリー操作"}
  query-utils
  (:use [clojure.tools.logging])
  (:require [clojure.string :as s]
            [input-parser.cond-parser :as cp]))

(def ^{:private true} prefixes #{:not})
(def ^{:private true} infixes #{:and :or})

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
          (cond (contains? prefixes op) (str (format "(%s " (name op)) (s/join (hoge (rest q))) ")")
                (contains? infixes op) (str "(" (s/join (format " %s " (name op)) (hoge (rest q))) ")")
                :else (throw (IllegalArgumentException. (format "unsupported operator: %s" (name op))))))))))

(defn to-where-clause
  "prefix->infix for translating text matching query to SQL WHERE-CLAUSE using LIKE"
  [q-str columns]
  (let [q (cp/parse q-str)]
    (debug (format "read query: %s" (pr-str q)))
    (cond (< 1 (count columns))
          (s/join " or " (for [c columns] (to-where-clause-aux q c)))
          (= 1 (count columns))
          (to-where-clause-aux q (first columns))
          :else (throw (IllegalArgumentException. (format "No columns was specified: %s" (pr-str columns)))))))
