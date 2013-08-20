;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Alert management functions."}
  nico.alert
  (:require [clojure.tools.logging :as log]
            [desktop-alert :as da]
            [nico.pgm :as pgm]
            [nico.thumbnail :as nt]
            [nico.ui.alert-dlg :as uad])
  (:import [java.util.concurrent Future]))

(def ^{:private true} DISPLAY-TIME 20000) ; アラートウィンドウの表示時間(ミリ秒)
(def ^{:private true} ICON-WIDTH  64)
(def ^{:private true} ICON-HEIGHT 64)

(defn init-alert []
  (da/init-alert (uad/dlg-width) (uad/dlg-height) :lr-bt 1))

(defn alert-pgm [id thumb-url]
  (let [^Future ftr (pgm/not-alerted id)
        thumbnail (nt/fetch thumb-url ICON-WIDTH ICON-HEIGHT)]
    (if-let [pgm (.get ftr)]
      (let [adlg (uad/alert-dlg pgm thumbnail)]
        (log/trace (str "alert: " id))
        (da/alert adlg DISPLAY-TIME))
      (log/trace (str "already alerted: " id)))))
