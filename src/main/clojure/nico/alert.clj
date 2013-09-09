;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Alert management functions."}
  nico.alert
  (:require [clojure.tools.logging :as log]
            [config-file :as cf]
            [desktop-alert :as da]
            [nico.pgm :as pgm]
            [nico.thumbnail :as nt]
            [nico.ui.alert-panel :as uap])
  (:import [java.util.concurrent Future]))

(def ^{:private true} DISPLAY-TIME 20000) ; アラートウィンドウの表示時間(ミリ秒)
(def ^{:private true} ICON-WIDTH  64)
(def ^{:private true} ICON-HEIGHT 64)

(defn init-alert [frame]
  (let [mode (condp = (cf/system)
               :mac :lr-tb
               :rl-bt)]
    (da/init-alert frame (uap/dlg-width) (uap/dlg-height) mode 1 150 uap/OPACITY (uap/get-shape))))

(defn alert-pgm [id thumb-url]
  (let [^Future ftr (pgm/not-alerted id)
        thumbnail (nt/fetch thumb-url ICON-WIDTH ICON-HEIGHT)]
    (if-let [pgm (.get ftr)]
      (let [content (uap/alert-panel pgm thumbnail)]
        (log/trace (str "alert: " id))
        (da/alert content DISPLAY-TIME))
      (log/trace (str "already alerted: " id)))))
