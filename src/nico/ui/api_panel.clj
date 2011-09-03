;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "コントロールパネル"}
  nico.ui.api-panel
  (:require [nico.pgm :as pgm]
	    [nico.rss :as rss]
	    [nico.updator :as nu]
	    [time-utils :as tu])
  (:import (java.awt BorderLayout Dimension)
	   (javax.swing ImageIcon JPanel JButton JProgressBar JLabel
			SpringLayout GroupLayout)
	   (javax.swing.border TitledBorder)))

(defn api-panel []
  (let [stat-panel (JPanel.)
	lstatus (JLabel. "ステータス")
	vstatus (JLabel. "有効なユーザータブが必要です")
	layout (BorderLayout.)]
    (doto (JPanel.)
      (.setBorder (TitledBorder. "API Status"))
      (.setLayout layout)
      (.add lstatus)(.add vstatus))))
