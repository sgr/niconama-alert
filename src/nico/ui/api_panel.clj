;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "APIによる番組情報取得状況表示パネル"}
  nico.ui.api-panel
  (:use [clojure.tools.swing-utils :only [do-swing add-action-listener]])
  (:require [nico.api-updator :as au]
	    [nico.ui.util :as uu]
	    [time-utils :as tu])
  (:import [java.awt Dimension]
	   [javax.swing GroupLayout ImageIcon JPanel JButton]
	   [javax.swing.border TitledBorder]))

(def ^{:private true} BTN-SIZE (Dimension. 80 30))
(def ^{:private true} STATUS-SIZE (Dimension. 140 30))
(def ^{:private true} THRESHOLD-SEC 600)

(defn api-panel []
  (let [panel (JPanel.)
	status (uu/mlabel "有効なユーザータブが必要です" STATUS-SIZE)
	cloader (.getClassLoader (class (fn [])))
	sicn (ImageIcon. (.getResource cloader "start.png"))
	picn (ImageIcon. (.getResource cloader "pause.png"))
	ricn (ImageIcon. (.getResource cloader "reload.png"))
	tbtn (JButton. sicn)
	cbtn (JButton. ricn)
	istr "APIによる番組情報取得はできません"
	tstr "APIによる番組情報取得を開始します"
	pstr "APIによる番組情報取得を中止します"
	cstr (format "開始から%d秒経過した番組情報取得をあきらめます" THRESHOLD-SEC)
	layout (GroupLayout. panel)
	hgrp (.createSequentialGroup layout)
	vgrp (.createSequentialGroup layout)]
    (au/add-api-hook :awaiting
                     (fn [] (condp = (au/get-awaiting-status)
                              :need_login (do-swing
                                           (doto tbtn
                                             (.setEnabled false)
                                             (.setToolTipText istr))
                                           (doto cbtn (.setEnabled false))
                                           (.setText status "有効なユーザータブが必要です"))
                              :ready (do-swing
                                      (doto tbtn
                                        (.setEnabled true)
                                        (.setToolTipText tstr))
				      (doto cbtn (.setEnabled false))
                                      (.setText status "接続できます"))
                              :aborted (do-swing
                                        (doto tbtn
                                          (.setEnabled true)
                                          (.setToolTipText tstr))
                                        (doto cbtn (.setEnabled false))
                                        (.setText status "接続できませんでした")))))
    (au/add-api-hook :connected
                     (fn [] (do-swing
                             (doto tbtn (.setEnabled false))
                             (doto cbtn (.setEnabled true))
                             (.setText status "接続完了"))))
    (au/add-api-hook :reconnecting
                     (fn [] (do-swing
                             (doto tbtn (.setEnabled false))
                             (doto cbtn (.setEnabled false))
                             (.setText status "接続中断のため再接続中"))))
    (au/add-api-hook :rate-updated
                     (fn [fetched-rate fetching-comm fetching-normal]
                       (do-swing
                        (doto tbtn (.setEnabled false))
                        (doto cbtn (.setEnabled true))
                        (.setText status (format "情報取得中: %d 番組\n追加: %d 番組/分"
                                                 (+ fetching-comm fetching-normal) fetched-rate)))))
    (doto tbtn
      (.setPreferredSize BTN-SIZE)
      (.setToolTipText istr)
      (.setEnabled false)
      (add-action-listener (fn [e] (au/start-update-api))))
    (doto cbtn
      (.setPreferredSize BTN-SIZE)
      (.setToolTipText cstr)
      (.setEnabled false)
      (add-action-listener (fn [e] (au/clear-normal-q THRESHOLD-SEC))))
    (doto hgrp
      (.addGroup (.. layout createParallelGroup
		     (addComponent status)))
      (.addGroup (.. layout createParallelGroup
		     (addComponent tbtn)))
      (.addGroup (.. layout createParallelGroup
		     (addComponent cbtn))))
    (doto vgrp
      (.addGroup (.. layout createParallelGroup
		     (addComponent status)
		     (addComponent tbtn)
		     (addComponent cbtn))))
    (doto layout
      (.setHorizontalGroup hgrp)
      (.setVerticalGroup vgrp)
      (.setAutoCreateGaps true)
      (.setAutoCreateContainerGaps true))
    (doto panel
      (.setBorder (TitledBorder. "API Status"))
      (.setLayout layout)
      (.add status)
      (.add tbtn))))
