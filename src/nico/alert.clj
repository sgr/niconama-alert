;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Alert management functions."}
  nico.alert
  (:use [clojure.tools.swing-utils :only [do-swing-and-wait]]
        [clojure.tools.logging]
        [nico.thumbnail :only [fetch]])
  (:require [concurrent-utils :as c]
            [time-utils :as tu]
            [nico.pgm :as pgm]
            [nico.thumbnail :as thumbnail]
            [nico.log :as l]
            [nico.ui.alert-dlg :as uad])
  (:import [java.awt GraphicsEnvironment]
           [java.awt.event WindowEvent]
           [javax.swing JDialog ImageIcon]
           [java.util.concurrent Future ThreadPoolExecutor TimeUnit]))

(def ^{:private true} DISPLAY-TIME 20) ; アラートウィンドウの表示時間(秒)
(def ^{:private true} KEEP-ALIVE 5) ; コアスレッド数を超えた処理待ちスレッドを保持する時間(秒)
(def ^{:private true} INTERVAL-DISPLAY 500) ; アラートウィンドウ表示処理の実行間隔(ミリ秒)

(def ^{:private true} ICON-WIDTH  64)
(def ^{:private true} ICON-HEIGHT 64)

(defn- divide-plats []
  (let [r (.getMaximumWindowBounds (GraphicsEnvironment/getLocalGraphicsEnvironment))
        aw (+ 5 (uad/dlg-width)), ah (+ 5(uad/dlg-height))
        rw (.width r), w (quot rw aw), rh (.height r), h (quot rh ah)]
    (vec (map #(let [[x y] %]
                 {:used false, :x (- rw (* x aw)), :y (- rh (* y ah))})
              (for [x (range 1 (inc w)) y (range 1 (inc h))] [x y])))))

(let [plats (atom (divide-plats))  ;; アラートダイアログの表示領域
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
                           (release-plat i plat))
              ^JDialog adlg (uad/alert-dlg pgm thumbicn release-fn)
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
  (defn alert-pgm [id thumb-url]
    (let [^Future ftr (pgm/not-alerted id)
          thumbnail (fetch thumb-url ICON-WIDTH ICON-HEIGHT)]
      (if-let [pgm (.get ftr)]
        (l/with-trace (str "alert: " id)
          (.execute ^ThreadPoolExecutor pool #(display-alert pgm thumbnail)))
        (trace (str "already alerted: " id))))))
