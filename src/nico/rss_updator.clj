;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコ生の番組情報を更新する。"}
    nico.rss-updator
  (:use [clojure.contrib.logging])
  (:require [nico.pgm :as pgm]
	    [nico.rss :as rss]
	    [time-utils :as tu]))

;; RSS updator
(let [counter (atom 1)
      latch (atom (java.util.concurrent.CountDownLatch. 1))
      hook-countdown (atom '())
      hook-fetching (atom '())
      hook-fetched (atom '())]
  (defn- add-hook-aux [a f] (reset! a (conj (deref a) f)))
  (defn add-hook [kind f]
    (condp = kind
	:countdown (add-hook-aux hook-countdown f)
	:fetching (add-hook-aux hook-fetching f)
	:fetched (add-hook-aux hook-fetched f)))
  (defn- fetch-rss []
    (try
      (loop [page 1, total (pgm/get-total), cur_total total, fetched #{}]
	(let [[cur_total cur_pgms] (rss/get-programs-from-rss-page (rss/get-nico-rss page))
	      fetched-upd (reduce conj fetched (map :id cur_pgms))
	      cfetched (count fetched-upd)]
	  (when (and (< 0 cur_total) (not (= (pgm/get-total) cur_total)))
	    (pgm/set-total cur_total))
	  ;; 番組の追加と取得状況のリアルタイム更新
	  (doseq [pgm cur_pgms] (when pgm (pgm/add pgm)))
	  (doseq [f @hook-fetching] (when f (f cfetched cur_total page)))
	  ;; 取得完了・中断・継続の判定
	  (cond
	   (or (>= cfetched (pgm/get-total)) (>= cfetched cur_total)) ;; 総番組数分取得したら、取得完了
	   (do
	     (info (format "finished fetching programs: %d" cfetched))
	     [:finished cfetched cur_total])
	   (= 0 (count cur_pgms)) ;; ひとつも番組が取れない場合は中止
	   (do
	     (warn (format "aborted fetching programs: %d" cfetched))
	     [:aborted cfetched cur_total])
	   :else
	   (recur (inc page), total cur_total fetched-upd))))
      (catch Exception e (error "failed fetching RSS" e)
	     [:error 0 (rss/get-programs-count)])))
  (defn set-counter [c] (reset! counter c))
  (defn update-rss []
    (try
      (loop [max @counter]
	(if (= 1 (.getCount @latch)) ;; pause中かどうか
	  (do (.await @latch))
	  (doseq [f @hook-countdown] (when f (f @counter max)))) ;; 待ち秒数表示
	(if (= 0 @counter) ; カウント0かどうか
	  (let [[result fetched total] (fetch-rss)
		new-max (condp = result
			    :finished 180
			    :aborted 120
			    :error 300)]
	    (set-counter new-max)
	    ;; 取得状況更新
	    (doseq [f @hook-fetched] (when f (f fetched total)))
	    (recur @counter))
	  (do
	    (Thread/sleep 1000)
	    (set-counter (dec @counter))
	    (recur max))))
      (catch Exception e (error "failed updating RSS" e))))
  (defn pause-rss-updator []
    (if (= 1 (.getCount @latch))
      (do (.countDown @latch) false)
      (do (reset! latch (java.util.concurrent.CountDownLatch. 1)) true))))

