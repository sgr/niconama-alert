;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "APIによる番組情報取得状況表示パネル"}
  nico.ui.api-panel
  (:require [nico.updator :as nu]
	    [nico.ui.util :as uu]
	    [time-utils :as tu])
  (:import (java.awt Dimension)
	   (javax.swing GroupLayout ImageIcon JPanel JButton)
	   (javax.swing.border TitledBorder)))

(def *btn-size* (Dimension. 80 30))
(def *status-size* (Dimension. 120 30))

(defn api-panel []
  (let [panel (JPanel.)
	status (uu/mlabel "有効なユーザータブが必要です" *status-size*)
	cloader (.getClassLoader (class (fn [])))
	sicn (ImageIcon. (.getResource cloader "start.png"))
	picn (ImageIcon. (.getResource cloader "pause.png"))
	tbtn (JButton. sicn)
	layout (GroupLayout. panel)
	hgrp (.createSequentialGroup layout)
	vgrp (.createSequentialGroup layout)]
    (doto tbtn
      (.setPreferredSize *btn-size*)
      (.setToolTipText "番組情報取得のために接続開始します。")
      (.setEnabled false))
    (doto hgrp
      (.addGroup (.. layout createParallelGroup
		     (addComponent status)))
      (.addGroup (.. layout createParallelGroup
		     (addComponent tbtn))))
    (doto vgrp
      (.addGroup (.. layout createParallelGroup
		     (addComponent status)
		     (addComponent tbtn))))
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
