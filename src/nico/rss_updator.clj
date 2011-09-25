;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコ生の番組情報を更新する。"}
    nico.rss-updator
  (:use [clojure.contrib.logging])
  (:require [nico.pgm :as pgm]
	    [nico.rss :as rss]
	    [time-utils :as tu]))

(defn- earliest-pubdate [earliest pgms]
  (reduce #(if (tu/earlier? %1 %2) %1 %2)
	  earliest (for [pgm pgms :when (:comm_id pgm)] (:pubdate pgm))))

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
      (loop [page 1
	     total (rss/get-programs-count)
	     cur_total total
	     earliest (tu/now)
	     fetched #{}]
	(let [rss (rss/get-nico-rss page)
	      cur_pgms (rss/get-programs-from-rss-page rss)
	      earliest-updated (earliest-pubdate earliest cur_pgms)
	      fetched-updated (if (pos? (count cur_pgms))
				(apply conj fetched (for [pgm cur_pgms] (:id pgm)))
				fetched)
	      cur_total (rss/get-programs-count rss)
	      cur_page (inc page)]
	  (when (< 0 cur_total) (pgm/set-total cur_total))
	  (doseq [pgm cur_pgms] (when pgm (pgm/add pgm)))
	  ;; 取得状況更新
	  (doseq [f @hook-fetching] (when f (f (count fetched) cur_total cur_page)))
	  ;; 取得完了・中断・継続の判定
	  (cond
	   (>= (+ (count fetched) (count cur_pgms)) cur_total) ;; 総番組数分取得したら、取得完了
	   (do
	     (info (format "finished fetching programs: %d" (+ (count fetched) (count cur_pgms))))
	     [:finished (count fetched) cur_total])
	   (= 0 (count cur_pgms)) ;; ひとつも番組が取れない場合は中止
	   (do
	     (warn (format "aborted fetching programs: %d" (count fetched)))
	     [:aborted (count fetched) cur_total])
	   :else
	   (recur cur_page total cur_total earliest-updated fetched-updated))))
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

