;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Alert management functions."}
  nico.alert
  (:use [clojure.contrib.swing-utils :only [do-swing*]])
  (:require [clojure.contrib.seq-utils :as cs]
	    [nico.ui.alert-dlg :as uad]
	    [nico.pgm :as pgm])
  (:import (java.awt GraphicsEnvironment)))

(def *interval* 20)

(defn- divide-plats []
  (let [r (.getMaximumWindowBounds (GraphicsEnvironment/getLocalGraphicsEnvironment))
	aw (+ 5 (uad/dlg-width)), ah (+ 5(uad/dlg-height))
	rw (.width r), w (quot rw aw), rh (.height r), h (quot rh ah)]
    (vec (map #(let [[x y] %]
		 {:used false, :x (- rw (* x aw)), :y (- rh (* y ah))})
	      (for [x (range 1 (inc w)) y (range 1 (inc h))] [x y])))))

(let [plats (atom (divide-plats))	;; アラートダイアログの表示領域
      queue (atom [])	;; アラート表示リクエストキュー
      latch (atom (java.util.concurrent.CountDownLatch. 1))]
  (defn- enqueue [req]
    (swap! queue conj req)
    (when (= 1 (.getCount @latch)) (.countDown @latch)))
  (defn- dequeue []
    (if (seq @queue)
      (let [top (first @queue)]
	(do
	  (swap! queue subvec 1)
	  (when (and (= 0 (.getCount @latch)) (= 0 (count @queue)))
	    (reset! latch (java.util.concurrent.CountDownLatch. 1))))
	top)
      nil))
  (defn- reserve-plat-aux [i]
    (if-not (:used (nth @plats i))
      (let [plat (nth @plats i)]
	(swap! plats assoc i (assoc plat :used true))
	[i plat])
      [nil nil]))
  (defn- reserve-plat []
    (if-let [i (some #(let [[i plat] %] (if (:used plat) i nil))
		     (reverse (cs/indexed @plats)))]
      (if (< i (dec (count @plats)))
	(reserve-plat-aux (inc i))
	(if-let [i (some #(let [[i plat] %] (if-not (:used plat) i nil))
			 (cs/indexed @plats))]
	  (reserve-plat-aux i)
	  [nil nil]))
      (reserve-plat-aux 0)))
  (defn- release-plat [i plat]
    (swap! plats assoc i (assoc plat :used false)))
  (defn alert-pgm [id]
    (when-let [pgm (pgm/get-pgm id)]
      (when (pgm/update-if-pgm (:id pgm) (fn [p] (not (:alerted p))) {:alerted true})
	(enqueue (pgm/get-pgm id)))))
  (defn gen-alerter []
    (Thread.
     (fn []
       (Thread/sleep 500)
       (when (= 1 (.getCount @latch)) (.await @latch))
       (if (< 0 (count @queue))
	 (do
	   (let [[i plat] (reserve-plat)]
	     (if i
	       (let [adlg (uad/alert-dlg (dequeue) (fn [] (release-plat i plat)))]
		 (.start (Thread. (fn []
				    (do-swing* :now
					       (fn []
						 (.setLocation adlg (:x plat) (:y plat))
						 (.setVisible adlg true)))
				    (Thread/sleep (* *interval* 1000))
				    (do-swing* :now
					       (fn []
						 (.setVisible adlg false)
						 (.dispose adlg)))
				    (release-plat i plat))))
		 (recur))
	       (recur))))
	 (recur))))))
