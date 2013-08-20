;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Alert management functions."}
  nico.alert
  (:use [clojure.tools.swing-utils :only [do-swing-and-wait]]
        [clojure.tools.logging]
        [nico.thumbnail :only [fetch]])
  (:require [concurrent-utils :as c]
            [desktop-alert :as da]
            [time-utils :as tu]
            [nico.pgm :as pgm]
            [nico.thumbnail :as thumbnail]
            [nico.log :as l]
            [nico.ui.alert-dlg :as uad])
  (:import [java.awt GraphicsEnvironment]
           [java.awt.event WindowEvent]
           [javax.swing JDialog ImageIcon]
           [java.util.concurrent Future ThreadPoolExecutor TimeUnit]))

(def ^{:private true} DISPLAY-TIME 20000) ; アラートウィンドウの表示時間(ミリ秒)
(def ^{:private true} ICON-WIDTH  64)
(def ^{:private true} ICON-HEIGHT 64)

(defn init-alert []
  (da/init-alert (uad/dlg-width) (uad/dlg-height) :rl-tb 1))

(defn alert-pgm [id thumb-url]
  (let [^Future ftr (pgm/not-alerted id)
        thumbnail (fetch thumb-url ICON-WIDTH ICON-HEIGHT)]
    (if-let [pgm (.get ftr)]
      (let [adlg (uad/alert-dlg pgm thumbnail)]
        (trace (str "alert: " id))
        (da/alert adlg DISPLAY-TIME))
      (trace (str "already alerted: " id)))))
