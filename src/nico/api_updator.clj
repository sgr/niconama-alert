;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコ生の番組情報を更新する。(API)"}
    nico.api-updator
  (:use [clojure.set :only [union]]
        [clojure.tools.logging])
  (:require [nico.pgm :as pgm]
	    [nico.api :as api]
	    [nico.scrape :as ns]
            [nico.log :as l]
	    [hook-utils :as hu]
	    [time-utils :as tu])
  (:import [java.util.concurrent Callable CountDownLatch LinkedBlockingQueue
            ThreadPoolExecutor TimeUnit]))

(def ^{:private true} RETRY-CONNECT 5)    ;; APIでXML Socketを開くのに失敗した際のリトライ回数上限
(def ^{:private true} RECONNECT-SEC 5)    ;; API接続が切れたときのリトライ間隔(秒)
(def ^{:private true} NTHREADS-COMM 1)    ;; 所属コミュニティの番組情報取得スレッドの最大数
(def ^{:private true} KEEP-ALIVE 5)       ;; 番組取得待機時間(秒)。これを過ぎると取得スレッドは終了する。
(def ^{:private true} LIMIT-ELAPSED 1200) ;; APIによる番組ID取得からこの秒以上経過したら情報取得を諦める。
(def ^{:private true} LIMIT-QUEUE 1000)    ;; スレッドプールにリトライ登録可能な数の目安
(def ^{:private true} RATE-UI-UPDATE 5)   ;; UIの更新間隔(秒)
(def ^{:private true} INTERVAL-SCRAPE 10)   ;; 番組情報再取得インターバル

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

(defn- create-pgm [pid cid received]
  (let [now (tu/now) lreceived (tu/format-time-long received)]
    ;; 繁忙期は番組ページ閲覧すら重い。番組ID受信から LIMIT-ELAPSED 秒経過していたら諦める。
    (if-not (tu/within? received now LIMIT-ELAPSED)
      (l/with-warn (format "too late to fetch: %s/%s received: %s, called: %s"
                           pid cid lreceived (tu/format-time-long now))
        :aborted)
      (if-let [pgm (create-pgm-from-scrapedinfo pid cid)]
	(l/with-trace (format "fetched pgm: %s %s pubdate: %s, received: %s, fetched_at: %s"
                              (:id pgm) (:title pgm) (:pubdate pgm) lreceived (:fetched_at pgm))
	  pgm)
	(l/with-warn (format "couldn't fetching pgm: %s/%s" pid cid)
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
  (let [c #(create-pgm pid cid received)]
    [[c] (atom {:pid pid :cid cid :uid uid :received received})]))
(defn- wft-pid [^nico.api-updator.WrappedFutureTask this] (:pid @(.state this)))
(defn- wft-cid [^nico.api-updator.WrappedFutureTask this] (:cid @(.state this)))
(defn- wft-uid [^nico.api-updator.WrappedFutureTask this] (:uid @(.state this)))
(defn- wft-received [^nico.api-updator.WrappedFutureTask this] (:received @(.state this)))

(let [latch (ref (CountDownLatch. 1)) ;; API取得に関するラッチ
      alert-statuses (ref {}) ;; ログイン成功したユーザータブの数だけ取得したalert-statusが入る。
      communities (ref #{})   ;; ログインしたユーザーの参加しているコミュニティの和集合
      awaiting-status (ref :need_login) ;; API updatorの状態
      received-rate (atom []) ;; API updatorが受信した最近1分間の番組数
      fetched-rate (atom [])] ;; API updatorにより登録された最近1分間の番組数
  (hu/defhook api :awaiting :connected :reconnecting :rate-updated)

  (defn get-awaiting-status [] @awaiting-status)
  (defn add-alert-status [as]
    (dosync
     (alter alert-statuses assoc (:user_id as) as)
     (ref-set communities (apply union (map #(-> % :comms set) (vals @alert-statuses))))
     (ref-set awaiting-status :ready))
    (run-api-hooks :awaiting))
  (defn start-update-api [] (.countDown ^CountDownLatch @latch))
  (defn- add-pgm [pgm]
    (when (some nil? (list (:title pgm) (:id pgm) (:pubdate pgm) (:fetched_at pgm)))
      (warn (format "Some nil properties found in: %s" (pr-str pgm))))
    (swap! fetched-rate conj (tu/now))
    (pgm/add pgm))
  (defn- create-executor [nthreads ^java.util.concurrent.BlockingQueue queue]
    (proxy [ThreadPoolExecutor] [0 nthreads KEEP-ALIVE TimeUnit/SECONDS queue]
      (afterExecute
        [r e]
        (proxy-super afterExecute r e)
        (when (and (nil? e) (instance? nico.api-updator.WrappedFutureTask r))
          (let [^nico.api-updator.WrappedFutureTask t r
                result (.get t) pid (.pid t) cid (.cid t) uid (.uid t)
                received (.received t) lreceived (tu/format-time-long received)]
            (cond
             (instance? nico.pgm.Pgm result) (add-pgm result)
             (= :failed result)
             (if (> LIMIT-QUEUE (.getActiveCount ^ThreadPoolExecutor this))
               (l/with-debug (format "retry task (%s/%s/%s %s)" pid cid uid lreceived)
                 (.execute ^ThreadPoolExecutor this (nico.api-updator.WrappedFutureTask. pid cid uid received)))
               (warn (format "too many tasks (%d) to retry (%s/%s/%s %s)"
                             (.size queue) pid cid uid lreceived)))))
          (.sleep TimeUnit/SECONDS INTERVAL-SCRAPE)))))
  (let [q (LinkedBlockingQueue.)
        pool (create-executor NTHREADS-COMM q)]
    (defn- enqueue [pid cid uid received]
      (.execute ^ThreadPoolExecutor pool (nico.api-updator.WrappedFutureTask. pid cid uid received)))
    (defn- create-task [pid cid uid received]
      (swap! received-rate conj (tu/now))
      (if (contains? @communities cid)
        (l/with-info (format "%s will be fetched because %s is joined community." pid cid)
          (enqueue pid cid uid received))
        (trace (format "%s: %s isn't your community." pid cid))))
    (defn request-fetch
      "RSSでタイトルが空の場合('<'を含んだ場合になるようだ)など、
       どうしてもスクレイピングで番組情報を取得したい場合に用いる。"
      [pid cid received]
      (l/with-debug (format "request force scraping with %s %s %s" pid cid received)
        (enqueue pid cid nil received)))
    (defn- update-api-aux []
      (try
	(api/listen (first (vals @alert-statuses)) (fn [] (run-api-hooks :connected)) create-task)
	(catch Exception e (l/with-warn e "** disconnected" nil))))
    (defn update-api []
      (letfn [(reset [astatus]
                (l/with-trace (format "reset to: %s" (name astatus))
                  (dosync
                   (ref-set awaiting-status astatus)
                   (ref-set latch (CountDownLatch. 1)))))]
	(loop [c RETRY-CONNECT]
          (when (= 1 (.getCount ^CountDownLatch @latch)) ;; pause中かどうか
            (do (run-api-hooks :awaiting)
                (.await ^CountDownLatch @latch)))
          (cond
           (= 0 c) (do (reset :aborted) (recur RETRY-CONNECT))
           (empty? @alert-statuses) (do (reset :need_login) (recur RETRY-CONNECT))
           :else (l/with-info (format "Will reconnect (%d/%d) after %d sec..."
                                      c RETRY-CONNECT RECONNECT-SEC)
                   (update-api-aux)
                   (.sleep TimeUnit/SECONDS RECONNECT-SEC)
                   (run-api-hooks :reconnecting)
                   (recur (dec c)))))))

    (defn update-rate []
      (letfn [(update-last [coll last-updated sec]
                (filter #(tu/within? % last-updated sec) coll))]
        (loop [last-updated (tu/now)]
          (when (= 1 (.getCount ^CountDownLatch @latch))
            (.await ^CountDownLatch @latch))
          (swap! received-rate update-last last-updated 60)
          (swap! fetched-rate  update-last last-updated 60)
          (run-api-hooks :rate-updated (count @received-rate) (count @fetched-rate) (.size q))
          (when (tu/within? last-updated (tu/now) RATE-UI-UPDATE)
            (.sleep TimeUnit/SECONDS RATE-UI-UPDATE))
          (recur (tu/now)))))))
