;; -*- coding: utf-8-unix -*-
(ns nico.pgm-test
  (:import (java.util Calendar Date))
  (:require [time-utils :as tu])
  (:use [nico.pgm] :reload-all)
  (:use [clojure.test]))

(defn- rm [pgm] (dosync (@#'nico.pgm/rem-aux (:id pgm))))
(defn- clear-pgms [] (doseq [id (keys (pgms))] (rm id)))


(with-test
  (defn- create-pgm
    [^Integer id title]
    (let [pid (format "lv%08d" id)]
      (nico.pgm.Pgm.
       (keyword pid) ;; id
       title ;; title
       (java.util.Date. (- (.getTime (tu/now)) (* 60000 id))) ;; pubdate
       (str "the description of " title) ;; desc
       "category" ;; category
       (str "http://live.nicovideo.jp/watch/" pid) ;; link
       "http://img.example.com" ;; thumbnail
       (str "the owner of " title) ;; owner_name
       (if (odd? id) true false) ;; member_only
       :community ;; type
       (str title " community") ;; comm_name
       (keyword (format "co%08d" id)) ;; comm_id
       false       ;; alerted
       (java.util.Date. (- (.getTime (tu/now)) (* 3000 id))) ;; fetched_at
       (java.util.Date. (- (.getTime (tu/now)) (* 3000 id)))))) ;; updated_at
  (defn- create-pgms [sq] (map #(create-pgm % (str "title" %)) sq))
  (defn- wait [from sec f]
    (loop []
      (Thread/sleep 1000)
      (if (tu/within? from (tu/now) sec) (recur) (f))))
  (defn- apply-single [f n] (doseq [pgm (create-pgms (range 0 n))] (f pgm)))
  (defn- apply-single-constrained [f n]
    (apply-single f (dec n))
    (wait (@#'nico.pgm/get-last-cleaned)
	  *interval-clean* #(f (create-pgm n "last program"))))
  (defn- divide-range [n d]
    (let [r (int (/ n d)), m (mod n d)]
      (for [s (range 0 d)] (range s n d))))
  (defn- apply-multi [f n d]
    (let [dr (divide-range n d),
	  fs (map #(future (doseq [pgm (create-pgms %)] (f pgm))) dr)]
      (doseq [f fs] (deref f))))
  (defn- apply-multi-constrained [f n d]
    (apply-multi f (dec n) d)
    (wait (@#'nico.pgm/get-last-cleaned)
	  *interval-clean* #(f (create-pgm n "last program"))))

  (clear-pgms)
  (testing "add and rem with single thread"
    (apply-single add 5000)
    (is (= 5000 (@#'nico.pgm/check-consistency)) "add 5000 programs")
    (apply-single rm 5000)
    (is (= 0 (@#'nico.pgm/check-consistency)) "rem 5000 programs")
    (clear-pgms))
  (testing "add and rem with multiple thread"
    (apply-multi add 5000 4)
    (is (= 5000 (@#'nico.pgm/check-consistency)) "add 5000 programs multi")
    (apply-multi rm 5000 4)
    (is (= 0 (@#'nico.pgm/check-consistency)) "rem 5000 programs multi")
    (clear-pgms))
  (testing "add with constraint"
    (let [total 3000, limit (* *scale* total)]
      (set-total total)
      (apply-single-constrained add 5000)
      (is (= limit (@#'nico.pgm/check-consistency)) "add 5000 programs single with max 3000")
      (clear-pgms)
      (apply-multi-constrained add 5000 4)
      (is (= limit (@#'nico.pgm/check-consistency)) "add 5000 programs multi with max 3000")
      (clear-pgms))))

