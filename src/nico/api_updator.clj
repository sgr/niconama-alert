;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコ生の番組情報を更新する。(API)"}
    nico.api-updator
  (:use [clojure.contrib.logging])
  (:require [nico.pgm :as pgm]
	    [nico.api :as api]
	    [hook-utils :as hu]
	    [time-utils :as tu]))

;; Official Alert API updator
(def *retry* 10)
(let [latch (ref (java.util.concurrent.CountDownLatch. 1))
      alert-status (ref '())
      awaiting-status (ref :need_login)
      fetched (atom [])]
  (hu/defhook :awaiting :connected :reconnecting :rate-updated)
  (defn get-awaiting-status [] @awaiting-status)
  (defn add-alert-status [as]
    (dosync
     (alter alert-status conj as)
     (ref-set awaiting-status :ready))
    (run-hooks :awaiting))
  (defn start-update-api [] (.countDown @latch))
  (defn- update-api-aux [ref-alert-status]
    (try
      (api/listen ref-alert-status
		  (fn [] (run-hooks :connected))
		  (fn [pgm]
		    (when (some nil?
				(list (:title pgm) (:id pgm) (:pubdate pgm) (:fetched_at pgm)))
		      (warn (format "Some nil properties found in: %s" (pr-str pgm))))
		    (let [now (tu/now)]
		      (swap! fetched conj now)
		      (pgm/add pgm))))
      (catch Exception e (warn "** disconnected" e) nil)))
  (defn update-api []
    (loop [c *retry*]
      (when (= 1 (.getCount @latch)) ;; pause中かどうか
	(do (run-hooks :awaiting)
	    (.await @latch)))
      (cond
       (= 0 c) (do
		 (dosync
		  (ref-set awaiting-status :aborted)
		  (ref-set latch (java.util.concurrent.CountDownLatch. 1)))
		 (recur *retry*))
       @alert-status (do
		       (update-api-aux alert-status)
		       (info "Will reconnect after 3 sec...")
		       (Thread/sleep 3000)
		       (run-hooks :reconnecting)
		       (recur (dec c))))))
  (defn get-fetched-rate [] (count @fetched))
  (defn update-rate []
    (loop [last-updated (tu/now)]
      (when (= 1 (.getCount @latch)) ;; pause中かどうか
	(.await @latch))
      (swap! fetched (fn [coll now] (filter #(tu/within? % now 60) coll)) (tu/now))
      (api/update-fetching)
      (run-hooks :rate-updated)
      (when (tu/within? last-updated (tu/now) 5) (Thread/sleep 5000))
      (recur (tu/now)))))
