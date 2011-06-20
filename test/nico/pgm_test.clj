;; -*- coding: utf-8-unix -*-
(ns nico.pgm-test
  (:import (java.util Calendar Date))
  (:require [time-utils :as tu])
  (:use [nico.pgm] :reload-all)
  (:use [clojure.test]))

(defn- create-pgm
  [^Integer id title]
  (let [pid (str "lv" (format "%08d" id))]
    (nico.pgm.Pgm.
     pid
     title
     (tu/now)
     (str "the description of " title)
     "category"
     (str "http://live.nicovideo.jp/watch/" pid)
     "http://img.example.com"
     (str "the owner of " title)
     (if (odd? id) true false)
     id
     "community"
     (* 3 id)
     (str title " community")
     (str "co" (format "%08d" id))
     false
     (tu/now))))

(defn- create-pgms [start end] (for [i (range start end)] (create-pgm i (str "title" i))))

;; 番組を足して、足されているか
;; 番組をマルチスレッドで足して、数が増えるか
;; 番組を削除して、削除されているか
;; 番組をマルチスレッドで削除して、数が減るか

(deftest test-add-pgms
  (let [th (fn [pgms] #(doseq [pgm pgms] (add-pgm pgm)))
	th1 (Thread. (th (create-pgms 0 1000)))
	th2 (Thread. (th (create-pgms 1000 2000)))
	th3 (Thread. (th (create-pgms 2000 3000)))
	th4 (Thread. (th (create-pgms 3000 4000)))]
    (is (= 4000 (do (.start th1) (.start th2) (.start th3) (.start th4)
		    (.join th1) (.join th2) (.join th3) (.join th4)
		    (count-pgms))))))

