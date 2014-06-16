;; -*- coding: utf-8-unix -*-
(ns nico.db-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [clojure.core.async :as ca]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [config-file :as cf]
            [nico.rss-test :as rsst]
            [nico.db :refer :all])
  (:import [java.util Date]))

(deftest query-generation-test
  (testing "where-clause (acceptable)"
    (is (= (where-clause "foo")
           "(ccol LIKE '%foo%')"))
    (is (= (where-clause "foo bar")
           "((ccol LIKE '%foo%') AND (ccol LIKE '%bar%'))"))
    (is (= (where-clause "foo bar baz")
           "((ccol LIKE '%foo%') AND (ccol LIKE '%bar%') AND (ccol LIKE '%baz%'))"))
    (is (= (where-clause "(and foo bar)")
           "((ccol LIKE '%foo%') AND (ccol LIKE '%bar%'))"))
    (is (= (where-clause "(or foo bar)")
           "((ccol LIKE '%foo%') OR (ccol LIKE '%bar%'))"))
    (is (= (where-clause "and foo")
           "((ccol LIKE '%and%') AND (ccol LIKE '%foo%'))"))
    (is (= (where-clause "or foo")
           "((ccol LIKE '%or%') AND (ccol LIKE '%foo%'))"))
    (is (= (where-clause "(and foo)")
           "((ccol LIKE '%foo%'))"))
    (is (= (where-clause "(or foo)")
           "((ccol LIKE '%foo%'))"))
    (is (= (where-clause "(not foo)")
           "(NOT (ccol LIKE '%foo%'))"))
    (is (= (where-clause "(AND foo bar)")
           "((ccol LIKE '%foo%') AND (ccol LIKE '%bar%'))"))
    (is (= (where-clause "(OR foo bar)")
           "((ccol LIKE '%foo%') OR (ccol LIKE '%bar%'))"))
    (is (= (where-clause "(NOT foo)")
           "(NOT (ccol LIKE '%foo%'))"))
    (is (= (where-clause "(not (and foo bar))")
           "(NOT ((ccol LIKE '%foo%') AND (ccol LIKE '%bar%')))"))
    (is (= (where-clause "(not (or foo bar))")
           "(NOT ((ccol LIKE '%foo%') OR (ccol LIKE '%bar%')))"))
    (is (= (where-clause "(and foo (not (or bar baz)))")
           "((ccol LIKE '%foo%') AND (NOT ((ccol LIKE '%bar%') OR (ccol LIKE '%baz%'))))"))
    (is (= (where-clause "and")
           "(ccol LIKE '%and%')"))
    (is (= (where-clause "or")
           "(ccol LIKE '%or%')"))
    (is (= (where-clause "not")
           "(ccol LIKE '%not%')"))
    (is (= (where-clause "and and")
           "((ccol LIKE '%and%') AND (ccol LIKE '%and%'))"))
    (is (= (where-clause "\"foo bar\"")
           "(ccol LIKE '%foo bar%')"))
    (is (= (where-clause "\"(foo bar)\"")
           "(ccol LIKE '%(foo bar)%')"))
    )
  (testing "where-clause (not acceptable)"
    (is (nil? (where-clause "(and)")))
    (is (nil? (where-clause "or)")))
    (is (nil? (where-clause "(not")))
    (is (nil? (where-clause "(not foo bar)")))
    (is (nil? (where-clause "(foo and bar)")))
    (is (nil? (where-clause "(foo or bar)")))
    )
  (testing "where-comms-clause"
    (let [where-comms-clause @#'nico.db/where-comms-clause]
      (is (= (where-comms-clause ["co000000" "co000001" "co000002"])
             "comm_id IN ('co000000','co000001','co000002')"))
      (is (= (where-comms-clause [])
             "comm_id IN ('NO_COMMUNITY')"))
      (is (= (where-comms-clause nil)
             "comm_id IN ('NO_COMMUNITY')"))
      ))
  (testing "sql-kwd"
    (let [sql-kwd @#'nico.db/sql-kwd]
      (is (= (sql-kwd "foo bar" #{:description :title})
             "SELECT *, description || title AS ccol FROM pgms WHERE ((ccol LIKE '%foo%') AND (ccol LIKE '%bar%')) ORDER BY open_time DESC"))
      (is (= (sql-kwd "foo bar" #{:description :title} 73)
             "SELECT *, description || title AS ccol FROM pgms WHERE ((ccol LIKE '%foo%') AND (ccol LIKE '%bar%')) ORDER BY open_time DESC LIMIT 73"))
      (is (= (sql-kwd "foo bar" #{:title})
             "SELECT *, title AS ccol FROM pgms WHERE ((ccol LIKE '%foo%') AND (ccol LIKE '%bar%')) ORDER BY open_time DESC"))
      (is (thrown? java.lang.AssertionError (sql-kwd "foo bar" #{})))
      (is (thrown? java.lang.AssertionError (sql-kwd nil #{:title})))
      (is (thrown? java.lang.AssertionError (sql-kwd "" #{:title})))
      (is (thrown? java.lang.AssertionError (sql-kwd "foo bar" #{"title"})))
      (is (thrown? java.lang.AssertionError (sql-kwd "foo bar" nil)))
      (is (thrown? java.lang.AssertionError (sql-kwd "foo bar" #{:title} "15")))
      (is (thrown? java.lang.AssertionError (sql-kwd "foo bar" #{:title} 0)))
      )))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- set-pgm-time [d pgm]
  (assoc pgm :id (format "lv%d" (rand-int Integer/MAX_VALUE)) :open_time d :fetched_at d :updated_at d))

(deftest ^{:stress true :db true :data true} db-test
  (let [TOTAL 5600
        oc-s (ca/chan)
        cc-db (boot oc-s)
        orx (rsst/rss-xml (io/resource "rss"))
        rxs (map rsst/rss-xml (rsst/rsss 180))
        pgms-official (rsst/extract-pgms orx :type :official)
        pgmss (->> rxs (map #(rsst/extract-pgms % :type :user)) (remove empty?))]

    (ca/go-loop [npgms 0]
      (when-let [c (ca/<! oc-s)]
        (recur
         (condp = (:status c)
           :db-stat  (:npgms c)
           :searched (do
                       (log/info (format " searched [%d] (" npgms)
                                 (s/join "," (map (fn [[k v]] (format "%s=%d" k (count v))) (:results c)))
                                 ")")
                       npgms)
           (log/errorf "Unknown command [%s]" (pr-str c))))))

    (ca/>!! cc-db {:cmd :set-query-kwd :id "fake0" :query "顔"
                   :target #{:owner_name :comm_name :title :description :category}})
    (ca/>!! cc-db {:cmd :set-query-kwd :id "fake1" :query "歌"
                   :target #{:owner_name :comm_name :title :description :category}})
    (ca/>!! cc-db {:cmd :set-query-kwd :id "fake2" :query "雑談"
                   :target #{:owner_name :comm_name :title :description :category}})
    (ca/>!! cc-db {:cmd :set-query-kwd :id "fake3" :query "一般"
                   :target #{:owner_name :comm_name :title :description :category}})

    (dotimes [n 100000]
      (log/infof "ADD-PGMS (%d)" n)
      (let [l (- (System/currentTimeMillis) 1800000)]
        (ca/>!! cc-db {:cmd :add-pgms :pgms (map #(set-pgm-time l %) pgms-official)})
        (doall
         (map-indexed
          (fn [i pgms] (ca/>!! cc-db {:cmd :add-pgms :pgms (map #(set-pgm-time (- l i) %) pgms)}))
          pgmss))
        (ca/>!! cc-db {:cmd :finish :total TOTAL})
        ))

    (ca/close! cc-db)
    (ca/close! oc-s)))
