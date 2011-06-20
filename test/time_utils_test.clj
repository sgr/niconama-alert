;; -*- coding: utf-8-unix -*-
(ns time-utils-test
  (:import (java.util Calendar Date))
  (:use [time-utils] :reload-all)
  (:use [clojure.test]))

(deftest test-earlier?
  (let [t (now)]
    (is (true? (earlier? t (Date. (+ 1000 (.getTime t))))))
    (is (false? (earlier? t (Date. (- 500 (.getTime t))))))
    (is (thrown? ClassCastException (earlier? (Object.) "now")))))

(deftest test-interval
  (let [t (now)]
    (is (= 1234 (interval t (Date. (+ 1234 (.getTime t))))))
    (is (thrown? ClassCastException (interval t "now")))))

(deftest test-minute
  (is (= 30 (minute (* 60 30 1000)))))
