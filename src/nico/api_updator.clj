;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコ生の番組情報を更新する。(API)"}
    nico.api-updator
  (:require [nico.pgm :as pgm]
	    [nico.api :as api]
	    [log-utils :as lu]
	    [time-utils :as tu]))

;; Official Alert API updator
(def *retry* 10)
(let [latch (atom (java.util.concurrent.CountDownLatch. 1))
      counter (atom *retry*)
      alert-status (atom nil)
      hook-awaiting (atom '())
      hook-connected (atom '())
      hook-aborted (atom '())
      hook-rate-updated (atom '())
      awaiting-status (atom :need_login)
      fetched (atom [])]
  (defn- add-hook-aux [a f] (reset! a (conj (deref a) f)))
  (defn add-hook [kind f]
    (condp = kind
	:awaiting (add-hook-aux hook-awaiting f)
	:connected (add-hook-aux hook-connected f)
	:aborted (add-hook-aux hook-aborted f)
	:rate-updated (add-hook-aux hook-rate-updated f)))
  (defn get-awaiting-status [] @awaiting-status)
  (defn set-alert-status [as]
    (reset! alert-status as)
    (.countDown @latch))
  (defn- api-update [alert-status]
    (try
      (api/listen alert-status
		  (fn [] (doseq [f @hook-connected] (f)))
		  (fn [pgm]
		    (let [now (tu/now)]
		      (swap! fetched conj now)
		      (println (format "[%s] %s" now (:title pgm)))
		      (pgm/add-pgm pgm))))
      (catch Exception e (lu/printe "" e) nil)))
  (defn update-api []
    (loop [c *retry*]
      (when (= 1 (.getCount @latch)) ;; pause中かどうか
	(do (doseq [f @hook-awaiting] (f))
	    (.await @latch)))
      (cond
       (= 0 c) (do
		 (doseq [f @hook-aborted] (f))
		 (reset! awaiting-status :aborted)
		 (reset! latch (java.util.concurrent.CountDownLatch. 1))
		 (recur *retry*))
       @alert-status (do
		       (api-update @alert-status)
		       (recur (dec c))))))
  (defn get-fetched-rate [] (count @fetched))
  (defn update-rate []
    (loop []
      (Thread/sleep 3000)
      (swap! fetched (fn [coll now] (filter #(tu/within? % now 60) coll)) (tu/now))
      (doseq [f @hook-rate-updated] (f))
      (recur)))
  )


