;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "クエリー操作"}
  query-utils
  (:require [clojure.string :as s])
  (:import [java.util Stack]))

(defn space? [^Character c] (some #(= % c) '(\space \tab \　)))
(defn quote? [^Character c] (= \" c))
(defn escape? [^Character c] (= \\ c))
(defn open? [^Character c] (some #(= % c) '(\( \（)))
(defn close? [^Character c] (some #(= % c) '(\) \）)))

(declare in-word in-space in-quote)

(defn- conj-top-stack [stack token]
  (if (= 0 (count token))
    stack
    (let [token (let [str-token (s/lower-case (s/join token))]
                  (if (= 0 (count (peek stack)))
                    (condp = str-token "and" :and "or" :or "not" :not)
                    str-token))]
      (conj (pop stack) (conj (peek stack) token)))))

(defn- check-end [stack token]
  (let [stack (if (< 0 (count token))
                (conj-top-stack stack (s/join token))
                stack)]
    (if (= 1 (count stack))
      (apply list (peek stack))
      (throw (IllegalArgumentException.
              (format "spurious open paren or missing close: %d" (count stack)))))))

(defn- check-open [src stack token]
  #(in-space (rest src)
             (if (< 0 (count token))
               (let [f (first stack) r (rest stack)]
                 (conj r (conj f (s/join token)) []))
               (conj stack []))
             []))

(defn- check-close [src stack token]
  (cond (= 1 (count stack))
        (let [f (peek stack)
              s (if (< 0 (count token))
                  (list (apply list (conj f (s/join token))))
                  (list (apply list f)))]
          #(in-space (rest src) s []))
        (< 1 (count stack))
        (let [s1 (peek stack)
              s2 (second stack)
              ns2 (if (< 0 (count token))
                    (conj s2 (apply list (conj s1 (s/join token))))
                    (conj s2 (apply list s1)))
              new-stack (conj (-> stack pop pop) ns2)]
          #(in-space (rest src) new-stack []))
        :else (throw (IllegalArgumentException.
                      (format "spurious close paren or missing open: %d"
                              (count stack))))))

(defn- in-space [src stack token]
;;  (prn (format "src: %s, stack: %s, token: %s" (pr-str src) (pr-str stack) (pr-str token)))
  (cond (= 0 (count src)) (check-end stack token)
        (open?  (first src)) (check-open src stack token)
        (close? (first src)) (check-close src stack token)
	(space? (first src)) #(in-space (rest src) stack [])
	(quote? (first src)) #(in-quote (rest src) stack [])
	:else                #(in-word  (rest src) stack [(first src)])))

(defn- in-word  [src stack token]
;;  (prn (format "src: %s, stack: %s, token: %s" (pr-str src) (pr-str stack) (pr-str token)))
  (cond (= 0 (count src)) (check-end stack token)
        (open?  (first src)) (check-open src stack token)
        (close? (first src)) (check-close src stack token)
	(space? (first src)) #(in-space (rest src) (conj-top-stack stack (s/join token)) [])
	(quote? (first src)) #(in-quote (rest src) (conj-top-stack stack (s/join token)) [])
	:else                #(in-word  (rest src) stack (conj token (first src)))))

(defn- in-quote [src stack token]
;;  (prn (format "src: %s, stack: %s, token: %s" (pr-str src) (pr-str stack) (pr-str token)))
  (cond (= 0 (count src)) (throw (IllegalArgumentException. "missing close quotation"))
	(quote? (first src))  #(in-space (rest src) (conj-top-stack stack (s/join token)) [])
	(escape? (first src)) (let [rsrc (rest src)]
				#(in-quote (rest rsrc) stack (conj token (first rsrc))))
	:else                 #(in-quote (rest src) stack (conj token (first src)))))

(defn- readq [^String q-str] (trampoline (in-space (char-array q-str) '([:and]) [])))

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
  (let [q (readq q-str)]
    (cond (< 1 (count columns))
          (s/join " or " (for [c columns] (to-where-clause-aux q c)))
          (= 1 (count columns))
          (to-where-clause-aux q (first columns))
          :else (throw (IllegalArgumentException. (format "No columns was specified: %s" (pr-str columns)))))))
