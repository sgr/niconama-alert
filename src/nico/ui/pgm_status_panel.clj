;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "コントロールパネル"}
  nico.ui.pgm-status-panel
  (:use [clojure.contrib.swing-utils :only [do-swing]])
  (:require [nico.pgm :as pgm]
	    [time-utils :as tu])
  (:import (java.awt Dimension)
	   (javax.swing GroupLayout ImageIcon JPanel JLabel)
	   (javax.swing.border TitledBorder)))

(def *panel-size* (Dimension. 350 40))

(defn pgm-status-panel []
  (let [pspanel (JPanel.)
	lpgms (JLabel. "番組数:"), vpgms (JLabel. "- / -")
	lfetched (JLabel. "最終更新:"), vfetched (JLabel. "----/--/-- --:--:--")
	layout (GroupLayout. pspanel)
	hgrp (.createSequentialGroup layout)
	vgrp (.createSequentialGroup layout)]
    (pgm/add-hook
     :updated
     (fn []
       (do-swing
	(.setText vpgms (format "%d / %d" (pgm/count-pgms) (pgm/get-total)))
	(.setText vfetched (tu/format-time-long (tu/now))))))
    (doto hgrp
      (.addGroup (.. layout createParallelGroup
		     (addComponent lpgms) (addComponent lfetched)))
      (.addGroup (.. layout createParallelGroup
		     (addComponent vpgms) (addComponent vfetched))))
    (doto vgrp
      (.addGroup (.. layout createParallelGroup
		     (addComponent lpgms) (addComponent vpgms)))
      (.addGroup (.. layout createParallelGroup
		     (addComponent lfetched) (addComponent vfetched))))
    (doto layout
      (.setHorizontalGroup hgrp)
      (.setVerticalGroup vgrp)
      (.setAutoCreateGaps true)
      (.setAutoCreateContainerGaps true))
    (doto pspanel
      (.setPreferredSize *panel-size*)
      (.setMinimumSize *panel-size*)
      (.setBorder (TitledBorder. "Programs Status"))
      (.setLayout layout))))
