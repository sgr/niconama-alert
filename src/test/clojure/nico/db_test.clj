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
      (is (= (sql-kwd "foo bar" #{:title :description})
             "SELECT *, title || description AS ccol FROM pgms WHERE ((ccol LIKE '%foo%') AND (ccol LIKE '%bar%')) ORDER BY open_time DESC"))
      (is (= (sql-kwd "foo bar" #{:title :description} 73)
             "SELECT *, title || description AS ccol FROM pgms WHERE ((ccol LIKE '%foo%') AND (ccol LIKE '%bar%')) ORDER BY open_time DESC LIMIT 73"))
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

(deftest row-pgm-test
  (testing "updates"
    (let [updates @#'nico.db/updates]
      (is (= {:foo 1 :bar 2 :baz 2} (updates {:foo 0 :bar 1 :baz 2} inc [:foo :bar])))
      (is (= {:foo 0 :bar 1 :baz 2} (updates {:foo 0 :bar 1 :baz 2} inc [])))
      (is (= {} (updates {} inc [])))
      (is (thrown? java.lang.AssertionError (updates nil inc [:foo :bar])))
      (is (thrown? java.lang.AssertionError (updates nil inc nil)))
      (is (thrown? java.lang.AssertionError (updates [] inc [:foo :bar])))
      ))
  (testing "pgm -> row"
    (let [to-row @#'nico.db/to-row
          now (Date.)
          nowl (.getTime now)
          pgm (nico.pgm.Pgm. "lv987654321" "タイトル" now now "番組説明" "一般,やってみた,ゲーム" "http://live.nicovideo.jp/watch/lv987654321" "http://icon.nimg.jp/community/111/co9999999.jpg?1388028442" "放送社名" false :community "コミュニティ名" "co9999999" now now)]
      (is (= (nico.pgm.Pgm. "lv987654321" "タイトル" nowl nowl "番組説明" "一般,やってみた,ゲーム" "http://live.nicovideo.jp/watch/lv987654321" "http://icon.nimg.jp/community/111/co9999999.jpg?1388028442" "放送社名" 0 0 "コミュニティ名" "co9999999" nowl nowl)
             (to-row pgm)))))
  (testing "row -> pgm"
    (let [to-pgm @#'nico.db/to-pgm
          now (Date.)
          nowl (.getTime now)
          row {:id "lv987654321" :title "タイトル" :open_time nowl :start_time nowl :description "番組説明" :category "一般,やってみた,ゲーム" :link "http://live.nicovideo.jp/watch/lv987654321" :thumbnail "http://icon.nimg.jp/community/111/co9999999.jpg?1388028442" :owner_name "放送社名" :member_only 0 :type 0 :comm_name "コミュニティ名" :comm_id "co9999999" :fetched_at nowl :updated_at nowl}]
      (is (= {:id "lv987654321" :title "タイトル" :open_time now :start_time now :description "番組説明" :category "一般,やってみた,ゲーム" :link "http://live.nicovideo.jp/watch/lv987654321" :thumbnail "http://icon.nimg.jp/community/111/co9999999.jpg?1388028442" :owner_name "放送社名" :member_only false :type 0 :comm_name "コミュニティ名" :comm_id "co9999999" :fetched_at now :updated_at now}
             (to-pgm row)))
      )))

(deftest greater?-test
  (is (true? (greater? "abcde" "abc")))
  (is (true? (greater? 10 -100000)))
  (is (false? (greater? "abc" "abcde")))
  (is (false? (greater? -100000 10)))
  (is (thrown? IllegalArgumentException (greater? 1 "abcde")))
  )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- set-pgm-time [^Date d pgm]
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
        (condp = (:status c)
          :db-stat  (recur (:npgms c))
          :searched (log/info (format "SEARCHED [%d] (" npgms)
                              (s/join "," (map (fn [[k v]] (format "%s=%d" k (count v))) (:results c)))
                              ")"))
        (recur npgms)))

    (ca/>!! cc-db {:cmd :set-query-kwd :id "fake0" :query "顔"
                   :target #{:owner_name :comm_name :title :description :category}})
    (ca/>!! cc-db {:cmd :set-query-kwd :id "fake1" :query "歌"
                   :target #{:owner_name :comm_name :title :description :category}})
    (ca/>!! cc-db {:cmd :set-query-kwd :id "fake2" :query "雑談"
                   :target #{:owner_name :comm_name :title :description :category}})
    (ca/>!! cc-db {:cmd :set-query-kwd :id "fake3" :query "一般"
                   :target #{:owner_name :comm_name :title :description :category}})

    (dotimes [n 100000]
      (log/infof "test %d" n)
      (let [l (- (System/currentTimeMillis) 1800000)
            d (Date. l)]
        (ca/>!! cc-db {:cmd :add-pgms :total TOTAL
                       :pgms (map #(set-pgm-time d %) pgms-official)})
        (doall
         (map-indexed
          (fn [i pgms]
            (let [ll (- l i)
                  d (Date. ll)
                  npgms (map #(set-pgm-time d %) pgms)]
              (ca/>!! cc-db {:cmd :add-pgms :total TOTAL :pgms npgms})))
          pgmss))
        (ca/>!! cc-db {:cmd :finish})
        ))

    (ca/close! cc-db)
    (ca/close! oc-s)))
