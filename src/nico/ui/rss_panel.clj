;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "番組情報取得パネル"}
  nico.ui.rss-panel
  (:use [clojure.contrib.swing-utils :only [do-swing add-action-listener]])
  (:require [nico.alert :as alert]
	    [nico.pgm :as pgm]
	    [nico.rss :as rss]
	    [nico.updator :as nu]
	    [time-utils :as tu])
  (:import (java.awt Dimension)
	   (javax.swing ImageIcon JPanel JButton JProgressBar JLabel SpringLayout GroupLayout)
	   (javax.swing.border TitledBorder)))

(def *btn-size* (Dimension. 80 30))
(def *bar-size* (Dimension. 100 20))

(defn rss-panel []
  (let [rss-panel (JPanel.)
	lstatus (JLabel. "ステータス:")
	vstatus (JLabel. "右のボタンでRSS取得開始")
	pbar (JProgressBar.)
	cloader (.getClassLoader (class (fn [])))
	ricn (ImageIcon. (.getResource cloader "reload.png"))
	sicn (ImageIcon. (.getResource cloader "start.png"))
	picn (ImageIcon. (.getResource cloader "pause.png"))
	rbtn (JButton. ricn), tbtn (JButton. sicn)
	stat-panel (JPanel.)
	stat-panel-layout (GroupLayout. stat-panel)
	stat-panel-hgrp (.createSequentialGroup stat-panel-layout)
	stat-panel-vgrp (.createSequentialGroup stat-panel-layout)
	rss-panel-layout (SpringLayout.)]
    (nu/add-hook
     :countdown
     (fn [sec max]
       (do-swing
	(.setText vstatus "RSS取得待機中")
	(.setString pbar (format "あと %d秒" sec))
	(.setMaximum pbar max) (.setValue pbar sec))))
    (nu/add-hook
     :fetching
     (fn [fetched-count total page]
       (do-swing
	(.setEnabled rbtn false) (.setEnabled tbtn false))
	(.setText vstatus "RSS情報取得中")
	(.setString pbar (format "%d / %d from page %d."
				 fetched-count total page))
	(.setMaximum pbar total)
	(.setValue pbar fetched-count)))
    (nu/add-hook
     :fetched
     (fn [fetched-count total]
       (do-swing (.setEnabled rbtn true) (.setEnabled tbtn true))
       (condp = total
	   0 (do ;; 通信エラーかサーバー落ちか、RSSが空っぽか。
	       (do-swing
		(.setText vstatus "サーバーとの通信エラー")))
	   fetched-count (do ;; 全て取得できた。
		     (do-swing
		      (.setText vstatus "RSS情報取得完了")
		      (.doClick tbtn)))
	   (do-swing
	    (.setText vstatus "RSS情報取得中断")))))
    (doto rbtn
      (.setPreferredSize *btn-size*)
      (.setToolTipText "RSS情報の取得をすぐに開始します")
      (.setEnabled false)
      (add-action-listener (fn [e] (nu/set-counter 1))))
    (let [tstr "RSS情報の取得を開始します", pstr "RSS情報の取得を一時停止します"]
      (doto tbtn
	(.setPreferredSize *btn-size*)
	(.setToolTipText tstr)
	(add-action-listener
	 (fn [e] (if (nu/pause-rss-updator)
		   (do-swing (doto tbtn
			       (.setIcon sicn) (.setToolTipText tstr))
			     (.setEnabled rbtn false))
		   (do-swing (doto tbtn
			       (.setIcon picn) (.setToolTipText pstr))
			     (.setEnabled rbtn true)))))))
    (doto pbar
      (.setPreferredSize *bar-size*)
      (.setStringPainted true))
    (doto stat-panel-hgrp
      (.addGroup (.. stat-panel-layout createParallelGroup
		     (addGroup (.. stat-panel-layout createSequentialGroup
				   (addComponent lstatus) (addComponent vstatus)))
		     (addComponent pbar))))
    (doto stat-panel-vgrp
      (.addGroup (.. stat-panel-layout createParallelGroup
		     (addComponent lstatus) (addComponent vstatus)))
      (.addGroup (.. stat-panel-layout createParallelGroup
		     (addComponent pbar))))
    (doto stat-panel-layout
      (.setHorizontalGroup stat-panel-hgrp)
      (.setVerticalGroup stat-panel-vgrp)
      (.setAutoCreateGaps true)
      (.setAutoCreateContainerGaps true))
    (doto stat-panel
      (.setLayout stat-panel-layout)
      (.add lstatus)
      (.add vstatus)
      (.add pbar))
    (doto rss-panel-layout
      (.putConstraint SpringLayout/NORTH stat-panel 1 SpringLayout/NORTH rss-panel)
      (.putConstraint SpringLayout/SOUTH stat-panel -1 SpringLayout/SOUTH rss-panel)
      (.putConstraint SpringLayout/NORTH tbtn 5 SpringLayout/NORTH rss-panel)
      (.putConstraint SpringLayout/SOUTH tbtn -5 SpringLayout/SOUTH rss-panel)
      (.putConstraint SpringLayout/NORTH rbtn 5 SpringLayout/NORTH rss-panel)
      (.putConstraint SpringLayout/SOUTH rbtn -5 SpringLayout/SOUTH rss-panel)
      (.putConstraint SpringLayout/WEST stat-panel 1 SpringLayout/WEST rss-panel)
      (.putConstraint SpringLayout/EAST stat-panel -5 SpringLayout/WEST tbtn)
      (.putConstraint SpringLayout/EAST tbtn -5 SpringLayout/WEST rbtn)
      (.putConstraint SpringLayout/EAST rbtn -5 SpringLayout/EAST rss-panel))
    (doto rss-panel
      (.setPreferredSize (Dimension. 500 60))
      (.setBorder (TitledBorder. "RSS Status"))
      (.setLayout rss-panel-layout)
      (.add stat-panel)
      (.add rbtn)
      (.add tbtn))))

