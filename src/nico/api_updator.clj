;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコ生の番組情報を更新する。(API)"}
    nico.api-updator
  (:use [clojure.contrib.logging])
  (:require [nico.pgm :as pgm]
	    [nico.api :as api]
	    [time-utils :as tu]))

;; Official Alert API updator
(def *retry* 10)
(let [latch (ref (java.util.concurrent.CountDownLatch. 1))
      alert-status (ref nil)
      awaiting-status (ref :need_login)
      hook-awaiting (atom '())
      hook-connected (atom '())
      hook-reconnecting (atom '())
      hook-rate-updated (atom '())
      fetched (atom [])]
  (defn- add-hook-aux [a f] (reset! a (conj (deref a) f)))
  (defn add-hook [kind f]
    (condp = kind
	:awaiting (add-hook-aux hook-awaiting f)
	:connected (add-hook-aux hook-connected f)
	:reconnecting (add-hook-aux hook-reconnecting f)
	:rate-updated (add-hook-aux hook-rate-updated f)))
  (defn get-awaiting-status [] @awaiting-status)
  (defn set-alert-status [as]
    (when-not @alert-status
      (dosync
       (ref-set alert-status as)
       (ref-set awaiting-status :ready)))
    (doseq [f @hook-awaiting] (f)))
  (defn start-update-api [] (.countDown @latch))
  (defn- api-update [alert-status]
    (try
      (api/listen alert-status
		  (fn [] (doseq [f @hook-connected] (f)))
		  (fn [pgm]
		    (when (some nil?
				(list (:title pgm) (:id pgm) (:pubdate pgm) (:fetched_at pgm)))
		      (warn (format "Some nil properties found in: %s" (prn-str pgm))))
		    (let [now (tu/now)]
		      (swap! fetched conj now)
		      (pgm/add pgm))))
      (catch Exception e (warn "** disconnected" e) nil)))
  (defn update-api []
    (loop [c *retry*]
      (when (= 1 (.getCount @latch)) ;; pause中かどうか
	(do (doseq [f @hook-awaiting] (f))
	    (.await @latch)))
      (cond
       (= 0 c) (do
		 (dosync
		  (ref-set awaiting-status :aborted)
		  (ref-set latch (java.util.concurrent.CountDownLatch. 1)))
		 (recur *retry*))
       @alert-status (do
		       (api-update @alert-status)
		       (info "Will reconnect after 3 sec...")
		       (Thread/sleep 3000)
		       (doseq [f @hook-reconnecting] (f))
		       (recur (dec c))))))
  (defn get-fetched-rate [] (count @fetched))
  (defn update-rate []
    (loop []
      (Thread/sleep 5000)
      (when (= 1 (.getCount @latch)) ;; pause中かどうか
	(.await @latch))
      (swap! fetched (fn [coll now] (filter #(tu/within? % now 60) coll)) (tu/now))
      (doseq [f @hook-rate-updated] (f))
      (recur)))
  )


