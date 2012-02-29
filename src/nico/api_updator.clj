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
  (:import (java.util.concurrent Callable CountDownLatch LinkedBlockingQueue
				 RejectedExecutionException ThreadPoolExecutor TimeUnit)))

(def *retry-connect* 3)    ;; APIでXML Socketを開くのに失敗した際のリトライ回数上限
(def *reconnect-sec* 2)    ;; API接続が切れたときのリトライ間隔(秒)
(def *nthreads-comm* 2)    ;; 所属コミュニティの番組情報取得スレッドの最大数
(def *nthreads-normal* 3)  ;; それ以外の番組情報取得スレッドの最大数
(def *keep-alive* 5)       ;; 番組取得待機時間(秒)。これを過ぎると取得スレッドは終了する。
(def *limit-elapsed* 1200) ;; APIによる番組ID取得からこの秒以上経過したら情報取得を諦める。
(def *limit-queue* 1000)    ;; スレッドプールにリトライ登録可能な数の目安
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

(gen-class
 :name nico.api-updator.WrappedFutureTask
 :extends java.util.concurrent.FutureTask
 :prefix "wft-"
 :constructors {[String String String java.util.Date] [java.util.concurrent.Callable]}
 :state state
 :init init
 :methods [[pid [] String]
	   [cid [] String]
	   [uid [] String]
	   [received [] java.util.Date]])
(defn- wft-init [pid cid uid received]
  (let [c #(create-pgm pid cid uid received)]
    [[c] (atom {:pid pid :cid cid :uid uid :received received})]))
(defn- wft-pid [this] (:pid @(.state this)))
(defn- wft-cid [this] (:cid @(.state this)))
(defn- wft-uid [this] (:uid @(.state this)))
(defn- wft-received [this] (:received @(.state this)))

(let [latch (ref (CountDownLatch. 1)) ;; API取得に関するラッチ
      alert-statuses (ref '()) ;; ログイン成功したユーザータブの数だけ取得したalert-statusが入る。
      awaiting-status (ref :need_login) ;; API updatorの状態
      fetched (atom [])] ;; API updatorにより登録された最近1分間の番組数
  (hu/defhook :awaiting :connected :reconnecting :rate-updated)
  (defn get-awaiting-status [] @awaiting-status)
  (defn get-fetched-rate [] (count @fetched))
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
  (defn- create-executor [nthreads queue]
    (proxy [ThreadPoolExecutor] [0 nthreads *keep-alive* TimeUnit/SECONDS queue]
      (beforeExecute
       [t r]
       (when (= 1 (.getCount @latch))
	 (do (debug "disconnected. awaiting reconnect...")
	     (.await @latch)
	     (debug "reconnected. fetching program information..."))))
      (afterExecute
       [r e]
       (when (nil? e)
	 (when (instance? nico.api-updator.WrappedFutureTask r)
	   (let [result (.get r) pid (.pid r) cid (.cid r) uid (.uid r) received (.received r)]
	     (cond
	      (instance? nico.pgm.Pgm result) (add-pgm result)
	      (= :failed result)
	      (if (> *limit-queue* (.getActiveCount this))
		(do
		  (.execute this
			    (nico.api-updator.WrappedFutureTask.
			     pid cid uid received))
		  (debug (format "retry task (%s/%s/%s %s)"
				 pid uid cid (tu/format-time-long received))))
		(debug (format "too many tasks (%d) to retry (%s/%s/%s %s)"
			       (.size queue) pid uid cid
			       (tu/format-time-long received)))))))))))
  (let [comm-q (LinkedBlockingQueue.)
	comm-executor (create-executor *nthreads-comm* comm-q) 
	normal-q (LinkedBlockingQueue.)
	normal-executor (create-executor *nthreads-normal* normal-q)]
    (defn clear-normal-q
      ([]
	 (trace (format "clear normal queue (%d -> 0)" (.size normal-q)))
	 (.clear normal-q))
      ([sec]
	 (let [c (.size normal-q), now (tu/now)]
	   (doseq [t (iterator-seq (.iterator normal-q))]
	     (when-not (tu/within? (.received t) now sec) (.remove normal-q t)))
	   (trace (format "clear normal queue (%d -> %d)" c (.size normal-q))))))
    (defn- create-task [pid cid uid received]
      (let [task (nico.api-updator.WrappedFutureTask. pid cid uid received)]
	(if (some #(contains? (set (:comms %)) (keyword cid)) @alert-statuses)
	  ;; 所属コミュニティの番組情報は専用のスレッドプールで(優先的に)取得
	  (do (trace (format "%s: %s is joined community." pid cid))
	      (.execute comm-executor task))
	  (do (trace (format "%s: %s isn't your community." pid cid))
	      (.execute normal-executor task)))))
    (defn- update-api-aux []
      (try
	(api/listen (first @alert-statuses) (fn [] (run-hooks :connected)) create-task)
	(catch Exception e
	  (warn "** disconnected" e)
	  nil)))
    (defn update-api []
      (letfn [(reset [astatus]
		     (trace (format "reset to: %s" (name astatus)))
		     (dosync
		      (ref-set awaiting-status astatus)
		      (ref-set latch (CountDownLatch. 1))))]
	(loop [c *retry-connect*]
	  (when (= 1 (.getCount @latch)) ;; pause中かどうか
	    (do (run-hooks :awaiting)
		(.await @latch)))
	  (cond
	   (= 0 c) (do (reset :aborted) (recur *retry-connect*))
	   (empty? @alert-statuses) (do (reset :need_login) (recur *retry-connect*))
	   :else (do
		   (update-api-aux)
		   (info (format "Will reconnect (%d/%d) after %d sec..."
				 c *retry-connect* *reconnect-sec*))
		   (Thread/sleep (* 1000 *reconnect-sec*))
		   (run-hooks :reconnecting)
		   (recur (dec c)))))))
    (defn update-rate []
      (loop [last-updated (tu/now)]
	(when (= 1 (.getCount @latch)) (.await @latch))
	(swap! fetched (fn [coll] (filter #(tu/within? % last-updated 60) coll)))
	(run-hooks :rate-updated (count @fetched) (.size comm-q) (.size normal-q))
	(when (tu/within? last-updated (tu/now) *rate-ui-update*)
	  (Thread/sleep (* 1000 *rate-ui-update*)))
	(recur (tu/now))))))
