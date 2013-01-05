;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコ生の番組情報を更新する。"}
    nico.rss-updator
  (:use [clojure.tools.logging])
  (:require [nico.pgm :as pgm]
	    [nico.rss :as rss]
	    [hook-utils :as hu]
            [log-utils :as l]
	    [time-utils :as tu])
  (:import [clojure.lang Keyword]
           [java.util.concurrent TimeUnit]))

(def ^{:private true} NO-PGMS-RETRY-LIMIT 3) ; RSSから一つも番組がとれない状況でリトライする限度
(def ^{:private true} RETRY-WAIT 5)

;; RSS updator
(let [counter (atom 1)
      latch (atom (java.util.concurrent.CountDownLatch. 1))]
  (hu/defhook rss :countdown :fetching :fetched)
  (defn- fetch-rss []
    (try
      (loop [page 1, total 0, pids #{}, no-pgms 0]
	(let [[cur_total cur_pgms] (rss/get-programs-from-rss page)
              total (max total cur_total)
	      pids-updated (reduce conj pids (map :id cur_pgms))
	      cids (count pids-updated)]
          (trace (format "fetched RSS(%d) cur_total: %d" page total))
	  (when (< (pgm/get-total) total) (pgm/set-total total))
          (trace (format "adding fetched pgms of RSS(%d)" page))
	  ;; 番組の追加と取得状況のリアルタイム更新
	  (doseq [pgm cur_pgms] (pgm/add pgm))
          (trace (format "added fetched pgms of RSS(%d)" page))
	  (run-rss-hooks :fetching cids total page)
	  ;; 取得完了・中断・継続の判定
	  (cond
	   (or (>= cids (pgm/get-total)) (>= cids total)) ;; 総番組数分取得したら、取得完了
	   (l/with-info (format "finished fetching programs: %d" cids)
             (pgm/set-total total)
	     [:finished cids total])
	   (= 0 (count cur_pgms)) ;; ひとつも番組が取れない場合は中止
           (let [no-pgms-now (inc no-pgms)]
             (if (> no-pgms-now NO-PGMS-RETRY-LIMIT)
               (l/with-warn (format "aborted fetching programs: %d, %d" cids no-pgms-now)
                 (pgm/set-total total)
                 [:aborted cids total])
               (do (.sleep TimeUnit/SECONDS RETRY-WAIT)
                   (recur (inc page) total pids-updated no-pgms-now))))
	   :else
	   (recur (inc page) total pids-updated no-pgms))))
      (catch Exception e (error e "failed fetching RSS")
	     [:error 0 0])))
  (defn set-counter [c] (reset! counter c))
  (defn update-rss []
    (try
      (loop [max @counter]
	(if (= 1 (.getCount @latch)) ;; pause中かどうか
	  (do (.await @latch))
	  (run-rss-hooks :countdown @counter max))
	(if (= 0 @counter) ; カウント0かどうか
	  (let [[result fetched total] (fetch-rss)]
	    (set-counter (condp = result
			     :finished 180
			     :aborted 120
			     :error 240))
	    (run-rss-hooks :fetched fetched total) ;; 取得状況更新
            (pgm/clean-old)
	    (recur @counter))
	  (do
	    (.sleep TimeUnit/SECONDS 1)
	    (set-counter (dec @counter))
	    (recur max))))
      (catch Exception e (error e "failed updating RSS"))))
  (defn pause-rss-updator []
    (if (= 1 (.getCount @latch))
      (do (.countDown @latch) false)
      (do (reset! latch (java.util.concurrent.CountDownLatch. 1)) true))))

