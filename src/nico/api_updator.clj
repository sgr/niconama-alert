;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコ生の番組情報を更新する。(API)"}
    nico.api-updator
  (:use [clojure.contrib.logging])
  (:require [nico.pgm :as pgm]
	    [nico.api :as api]
	    [nico.scrape :as ns]
	    [hook-utils :as hu]
	    [time-utils :as tu])
  (:import (java.util.concurrent Callable Executors RejectedExecutionException)))

(def *retry-connect* 10)   ;; APIでXML Socketを開くのに失敗した際のリトライ回数上限
(def *reconnect-sec* 3)    ;; API接続が切れたときのリトライ間隔(秒)
(def *nthreads-comm* 1)    ;; 所属コミュニティの番組情報取得スレッド数
(def *nthreads-normal* 3)  ;; それ以外の番組情報取得スレッド数
(def *limit-elapsed* 1200) ;; APIによる番組ID取得からこの秒以上経過したら情報取得を諦める。
(def *limit-pool* 999)     ;; スレッドプールにリトライ登録可能な数の目安
(def *wait-resubmit* 2000) ;; スレッドプールにsubmit出来無い場合のリトライ間隔(ミリ秒)
(def *rate-ui-update* 5)   ;; UIの更新間隔(秒)

(defn- create-pgm-from-scrapedinfo
  [pid cid]
  (if-let [info (ns/fetch-pgm-info pid)]
    (nico.pgm.Pgm.
     (keyword pid)
     (:title info)
     (:pubdate info)
     (:desc info)
     (:category info)
     (:link info)
     (:thumbnail info)
     (:owner_name info)
     (:member_only info)
     (:type info)
     (:comm_name info)
     (keyword cid)
     false
     (:fetched_at info)
     (:updated_at info))
    nil))

(defn- create-pgm [pid cid uid received]
  (let [now (tu/now)]
    ;; 繁忙期は番組ページ閲覧すら重い。番組ID受信から*limit-elapsed*秒経過していたら諦める。
    (if-not (tu/within? received now *limit-elapsed*)
      (do (warn (format "too late to fetch: %s/%s/%s received: %s, called: %s"
			pid cid uid (tu/format-time-long received) (tu/format-time-long now)))
	  :aborted)
      (if-let [pgm (create-pgm-from-scrapedinfo pid cid)]
	(do (trace (format "fetched pgm: %s %s pubdate: %s, received: %s, fetched_at: %s"
			   (:id pgm) (:title pgm) (:pubdate pgm)
			   (tu/format-time-long received) (:fetched_at pgm)))
	  pgm)
	(do (warn (format "couldn't fetching pgm: %s/%s/%s" pid cid uid))
	    :failed)))))

(let [latch (ref (java.util.concurrent.CountDownLatch. 1))
      alert-statuses (ref '()) ;; ログイン成功したユーザータブの数だけ取得したalert-statusが入る。
      awaiting-status (ref :need_login) ;; API updatorの状態
      fetched (atom []) ;; API updatorにより登録された最近1分間の番組数
      ;; 以下は番組情報取得スレッドプール。所属コミュニティ用とそれ以外用。
      comm-pool (Executors/newFixedThreadPool *nthreads-comm*), comm-futures (ref {})
      normal-pool (Executors/newFixedThreadPool *nthreads-normal*), normal-futures (ref {})]
  (hu/defhook :awaiting :connected :reconnecting :rate-updated)
  (defn get-awaiting-status [] @awaiting-status)
  (defn add-alert-status [as]
    (dosync
     (alter alert-statuses conj as)
     (ref-set awaiting-status :ready))
    (run-hooks :awaiting))
  (defn start-update-api [] (.countDown @latch))
  (defn- add-pgm [pgm]
    (when (some nil? (list (:title pgm) (:id pgm) (:pubdate pgm) (:fetched_at pgm)))
      (warn (format "Some nil properties found in: %s" (pr-str pgm))))
    (swap! fetched conj (tu/now))
    (pgm/add pgm))
  (defn- create-task [pid cid uid received]
    (let [task (proxy [Callable] []
		 (call [] (let [r (create-pgm pid cid uid received)]
			    (if (instance? nico.pgm.Pgm r) (do (add-pgm r) :succeeded) r))))]
      (if (some #(contains? (set (:comms %)) (keyword cid)) @alert-statuses)
	;; 所属コミュニティの番組情報は専用のスレッドプールで(優先的に)取得
	(do (trace (format "%s: %s is joined community." pid cid))
	    (let [f (.submit comm-pool ^Callable task)]
	      (dosync (alter comm-futures conj [f task]))))
	(do (trace (format "%s: %s isn't your community." pid cid))
	    (let [f (.submit normal-pool ^Callable task)]
	      (dosync (alter normal-futures conj [f task])))))))
  (defn- update-api-aux []
    (try
      (api/listen (first @alert-statuses) (fn [] (run-hooks :connected)) create-task)
      (catch Exception e (warn "** disconnected" e) nil)))
  (defn update-api []
    (letfn [(reset [astatus]
		   (trace (format "reset to: %s" (name astatus)))
		   (dosync
		    (ref-set awaiting-status astatus)
		    (ref-set latch (java.util.concurrent.CountDownLatch. 1))))]
    (loop [c *retry-connect*]
      (when (= 1 (.getCount @latch)) ;; pause中かどうか
	(do (run-hooks :awaiting)
	    (.await @latch)))
      (cond
       (= 0 c) (do (reset :aborted) (recur *retry-connect*))
       (empty? @alert-statuses) (do (reset :need_login) (recur *retry-connect*))
       :else (do
	       (update-api-aux)
	       (info (format "Will reconnect (%d/%d) after %d sec..." c *retry-connect* *reconnect-sec*))
	       (Thread/sleep (* 1000 *reconnect-sec*))
	       (run-hooks :reconnecting)
	       (recur (dec c)))))))
  (defn- update-fetching []
    (let [comm-retries (ref '()) normal-retries (ref '())]
      (letfn [(sweep [futures-map]
		     (loop [done-futures '() failed-tasks '() m futures-map]
		       (if (= 0 (count m))
			 [done-futures failed-tasks]
			 (let [[f task] (first m)]
			   (if (or (.isDone f) (.isCancelled f))
			     (if (= :failed (.get f))
			       (recur (conj done-futures f) (conj failed-tasks task) (rest m))
			       (recur (conj done-futures f) failed-tasks (rest m)))
			     (recur done-futures failed-tasks (rest m)))))))
	      (submit-aux [t pool]
			  (try (.submit pool ^Callable t)
			       (catch RejectedExecutionException e
				 (error (format "rejected execution.") e) nil)))
	      (submit [m task pool]
		      (assoc m (loop [t task]
				 (if-let [f (submit-aux t pool)] f
					 (do (Thread/sleep *wait-resubmit*) (recur t))))
			     task))]
	;; 終了したタスクを削除。取得失敗したタスクはリトライキューに追加。
	(let [[done-comm-futures failed-comm-tasks] (sweep @comm-futures)
	      [done-normal-futures failed-normal-tasks] (sweep @normal-futures)]
	  (dosync
	   (alter comm-futures #(apply dissoc % done-comm-futures))
	   (ref-set comm-retries failed-comm-tasks)
	   (alter normal-futures #(apply dissoc % done-normal-futures))
	   (ref-set normal-retries failed-normal-tasks)))
	;; リトライキューのタスクを再度スレッドプールに登録する。
	;; スレッドプールへの登録が副作用であるためトランザクションを分けざるをえない。
	;; この間*-futuresに追加が発生する可能性があるが、矛盾は生じない。
	;; 但し*limit-pool*を超えるリクエストがスレッドプールにたまっている場合はリトライを諦める。
	(let [retry-comm-futures (if (< *limit-pool* (count @comm-futures))
				   (do (debug (format "too many comm futures (%d) to retry (%d)"
						      (count @comm-futures) (count @comm-retries)))
				       {})
				   (reduce #(submit %1 %2 comm-pool) {} @comm-retries))
	      retry-normal-futures (if (< *limit-pool* (count @normal-futures))
				     (do (debug (format "too many normal futures (%d) to retry (%d)"
							(count @normal-futures) (count @normal-retries)))
					 {})
				     (reduce #(submit %1 %2 normal-pool) {} @normal-retries))]
	  (dosync
	   (alter comm-futures merge retry-comm-futures)
	   (alter normal-futures merge retry-normal-futures)))
	(trace
	 (format "comm-retries: %d, normal-retries: %d, comm-futures: %d, normal-futures: %d"
		 (count @comm-retries) (count @normal-retries)
		 (count @comm-futures) (count @normal-futures))))))
  (defn get-fetched-rate [] (count @fetched))
  (defn update-rate []
    (loop [last-updated (tu/now)]
      (when (= 1 (.getCount @latch)) (.await @latch))
      (swap! fetched (fn [coll] (filter #(tu/within? % last-updated 60) coll)))
      (update-fetching)
      (run-hooks :rate-updated (count @fetched) (count @comm-futures) (count @normal-futures))
      (when (tu/within? last-updated (tu/now) *rate-ui-update*)
	(Thread/sleep (* 1000 *rate-ui-update*)))
      (recur (tu/now)))))
