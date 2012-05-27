;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Alert management functions."}
  nico.alert
  (:use [clojure.tools.swing-utils :only [do-swing-and-wait]]
	[clojure.tools.logging])
  (:require [log-utils :as l]
            [time-utils :as tu]
	    [nico.ui.alert-dlg :as uad]
	    [nico.pgm :as pgm])
  (:import [java.awt GraphicsEnvironment]
           [java.util.concurrent LinkedBlockingQueue ThreadPoolExecutor TimeUnit]))

(def ^{:private true} DISPLAY-TIME 20) ; アラートウィンドウの表示時間(秒)
(def ^{:private true} KEEP-ALIVE 5) ; コアスレッド数を超えた処理待ちスレッドを保持する時間(秒)
(def ^{:private true} EXEC-INTERVAL 500) ; アラートウィンドウ表示処理の実行間隔(ミリ秒)

(defn- divide-plats []
  (let [r (.getMaximumWindowBounds (GraphicsEnvironment/getLocalGraphicsEnvironment))
	aw (+ 5 (uad/dlg-width)), ah (+ 5(uad/dlg-height))
	rw (.width r), w (quot rw aw), rh (.height r), h (quot rh ah)]
    (vec (map #(let [[x y] %]
		 {:used false, :x (- rw (* x aw)), :y (- rh (* y ah))})
	      (for [x (range 1 (inc w)) y (range 1 (inc h))] [x y])))))

(defn- periodic-executor [queue]
  (proxy [ThreadPoolExecutor] [0 1 KEEP-ALIVE TimeUnit/SECONDS queue]
    (beforeExecute
      [t r]
      (.sleep TimeUnit/MILLISECONDS EXEC-INTERVAL)
      (proxy-super beforeExecute t r))))

(let [plats (atom (divide-plats))	;; アラートダイアログの表示領域
      pool (periodic-executor (LinkedBlockingQueue.))
      sentinel (Object.)
      last-modified (atom (tu/now))]
  (defn- reserve-plat-aux [i]
    (if-not (:used (nth @plats i))
      (let [plat (nth @plats i)]
	(swap! plats assoc i (assoc plat :used true))
	[i plat])
      [nil nil]))
  (defn- reserve-plat-A []
    (if-let [i (some #(let [[i plat] %] (if (:used plat) i nil))
		     (reverse (map-indexed vector @plats)))]
      (if (< i (dec (count @plats)))
	(reserve-plat-aux (inc i))
	(if-let [i (some #(let [[i plat] %] (if-not (:used plat) i nil))
			 (map-indexed vector @plats))]
	  (reserve-plat-aux i)
	  [nil nil]))
      (reserve-plat-aux 0)))
  (defn- reserve-plat-B []
    (let [iplat (some #(let [[i plat] %] (if-not (:used plat) [i plat] nil))
		      (map-indexed vector @plats))]
      (if-let [[i plat] iplat]
	(do
	  (swap! plats assoc i (assoc plat :used true))
	  iplat)
	[nil nil])))
  (defn- release-plat [i plat]
    (swap! plats assoc i (assoc plat :used false)))
  (defn- alert-aux [pgm]
    (let [now (tu/now)
          [i plat] (if (tu/within? @last-modified now 5) (reserve-plat-A) (reserve-plat-B))]
      (if i
        (l/with-debug (str "display alert dialog: " (:id pgm))
          (let [adlg (uad/alert-dlg pgm #(release-plat i plat))]
            (reset! last-modified now)
            (future 
              (do-swing-and-wait (doto adlg
                                   (.setLocation (:x plat) (:y plat))
                                   (.setVisible true)))
              (.sleep TimeUnit/SECONDS DISPLAY-TIME)
              (do-swing-and-wait (doto adlg
                                   (.setVisible false)
                                   (.dispose)))
              (release-plat i plat))))
        (l/with-trace (str "waiting plats.." (:id pgm))
            (.sleep TimeUnit/SECONDS 5)
            (recur pgm)))))
  (defn alert-pgm [id]
    (locking sentinel
      (when-let [pgm (pgm/get-pgm id)]
        (if-not (:alerted pgm)
          (l/with-trace (str "alert: " id)
            (.execute pool #(alert-aux pgm))
            (pgm/update-alerted id))
          (trace (str "already alerted: " id)))))))

