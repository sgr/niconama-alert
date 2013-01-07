;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Alert management functions."}
  nico.alert
  (:use [clojure.tools.swing-utils :only [do-swing-and-wait]]
	[clojure.tools.logging])
  (:require [concurrent-utils :as c]
            [log-utils :as l]
            [time-utils :as tu]
            [nico.thumbnail :as thumbnail]
	    [nico.ui.alert-dlg :as uad]
	    [nico.pgm :as pgm])
  (:import [java.awt GraphicsEnvironment]
           [java.awt.event WindowEvent]
           [javax.swing ImageIcon]
           [java.util.concurrent TimeUnit]))

(def ^{:private true} DISPLAY-TIME 20) ; アラートウィンドウの表示時間(秒)
(def ^{:private true} KEEP-ALIVE 5) ; コアスレッド数を超えた処理待ちスレッドを保持する時間(秒)
(def ^{:private true} INTERVAL-DISPLAY 500) ; アラートウィンドウ表示処理の実行間隔(ミリ秒)

(defn- divide-plats []
  (let [r (.getMaximumWindowBounds (GraphicsEnvironment/getLocalGraphicsEnvironment))
	aw (+ 5 (uad/dlg-width)), ah (+ 5(uad/dlg-height))
	rw (.width r), w (quot rw aw), rh (.height r), h (quot rh ah)]
    (vec (map #(let [[x y] %]
		 {:used false, :x (- rw (* x aw)), :y (- rh (* y ah))})
	      (for [x (range 1 (inc w)) y (range 1 (inc h))] [x y])))))

(let [plats (atom (divide-plats))	;; アラートダイアログの表示領域
      [queue pool] (c/periodic-executor 1 TimeUnit/MILLISECONDS INTERVAL-DISPLAY)
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
  (defn- display-alert [^nico.pgm.Pgm pgm ^ImageIcon thumbicn]
    (let [now (tu/now)
          [i plat] (if (tu/within? @last-modified now 5) (reserve-plat-A) (reserve-plat-B))]
      (if i
        (let [release-fn (fn []
                           (if (not= thumbicn thumbnail/NO-IMAGE)
                             (.flush (.getImage thumbicn))
                             (debug "NO-IMAGE, no flush"))
                           (release-plat i plat))
              adlg (uad/alert-dlg pgm thumbicn release-fn)
              worker (proxy [javax.swing.SwingWorker][]
                       (doInBackground [] (.sleep TimeUnit/SECONDS DISPLAY-TIME))
                       (done []
                         (doto adlg
                           (.setVisible false)
                           (.dispatchEvent (WindowEvent. adlg WindowEvent/WINDOW_CLOSING))
                           (.dispose))
                         (release-fn)))]
          (reset! last-modified now)
          (do-swing-and-wait (doto adlg
                               (.setLocation (:x plat) (:y plat))
                               (.setVisible true)))
          (.execute worker))
        (l/with-trace (str "waiting plats.." (:id pgm))
            (.sleep TimeUnit/SECONDS 5)
            (recur pgm thumbicn)))))
  (defn alert-pgm [id]
    (if-let [pgm (pgm/not-alerted id)]
      (let [thumbnail (pgm/get-comm-thumbnail (:comm_id pgm))]
        (trace (str "alert: " id))
        (.execute pool #(display-alert pgm thumbnail)))
      (trace (str "already alerted: " id)))))
