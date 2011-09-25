;; -*- coding: utf-8-unix -*-
(ns nico.pgm-test
  (:import (java.util Calendar Date))
  (:require [time-utils :as tu])
  (:use [nico.pgm] :reload-all)
  (:use [clojure.test]))

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
     (java.util.Date. (- (.getTime (tu/now)) (* 1000 id))) ;; fetched_at
     (java.util.Date. (- (.getTime (tu/now)) (* 1000 id)))))) ;; updated_at

(defn- create-pgms [start end] (for [i (range start end)] (create-pgm i (str "title" i))))

;; 番組を足して、足されているか
;; 番組をマルチスレッドで足して、数が増えるか
;; 番組を削除して、削除されているか
;; 番組をマルチスレッドで削除して、数が減るか

(with-test
  (defn check []
    (let [npgms (count-pgms)
	  ncomm (@#'nico.pgm/count-comm)
	  npubdate (@#'nico.pgm/count-pubdate)
	  nupdated (@#'nico.pgm/count-updated-at)
	  nelapsed (@#'nico.pgm/count-elapsed)]
      (if (= npgms npubdate nupdated nelapsed)
	npgms
	(do (println (format " PGMS:    %d" npgms))
	    (println (format " COMM:    %d" ncomm))
	    (println (format " PUBDATE: %d" npubdate))
	    (println (format " UPDATED: %d" nupdated))
	    (println (format " ELAPSED: %d" nelapsed))
	    nil))))
  (defn- fit? [n] (= n (check)))
  (defn apply-single [f n]
    (doseq [pgm (create-pgms 0 5000)] (f pgm)))
  (defn rm [pgm] (dosync (@#'nico.pgm/rem-aux (:id pgm))))
  (defn- divide-range [n d]
    (let [s (range 0 (inc n) (int (/ n d))), c (dec (count s))]
      (for [i (range 0 c)] (list (nth s i) (nth s (inc i))))))
  (defn apply-multi [f n m]
    (let [dr (divide-range n m), c (count dr)
	  fs (map #(future (doseq [pgm (apply create-pgms %)] (f pgm))) dr)]
      (doseq [f fs] (deref f))
      (count-pgms)))
  (defn clear-pgms [] (doseq [id (keys (pgms))] (rm id)))

  (testing "add and rem with single thread"
    (is (do (apply-single add 5000) (fit? 5000)) "add 5000 programs")
    (is (do (apply-single rm 5000) (fit? 0)) "rem 5000 programs")
    (clear-pgms))
  (testing "add and rem with multiple thread"
    (is (do (apply-multi add 5000 4) (fit? 5000)) "add 5000 programs multi")
    (is (do (apply-multi rm 5000 4) (fit? 0)) "rem 5000 programs multi")
    (clear-pgms))
  (testing "add with constraint"
    (set-total 3000)
    (is (do (apply-single add 5000) (fit? (* *scale* 3000))) "add 5000 programs multi with max 3000")
    (clear-pgms)
    (is (do (apply-multi add 5000 4) (fit? (* *scale* 3000))) "add 5000 programs multi with max 3000")
    (clear-pgms)))

