;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "番組情報取得パネル"}
  nico.ui.rss-panel
  (:use [clojure.contrib.swing-utils :only [do-swing add-action-listener]])
  (:require [nico.rss-updator :as nru])
  (:import (java.awt Dimension)
	   (javax.swing GroupLayout ImageIcon JPanel JButton JProgressBar JLabel)
	   (javax.swing.border TitledBorder)))

(def *btn-size* (Dimension. 80 30))
(def *bar-size* (Dimension. 200 20))

(defn rss-panel []
  (let [rss-panel (JPanel.)
	status (JLabel. "右のボタンでRSS取得開始")
	pbar (JProgressBar.)
	cloader (.getClassLoader (class (fn [])))
	ricn (ImageIcon. (.getResource cloader "reload.png"))
	sicn (ImageIcon. (.getResource cloader "start.png"))
	picn (ImageIcon. (.getResource cloader "pause.png"))
	rbtn (JButton. ricn), tbtn (JButton. sicn)
	layout (GroupLayout. rss-panel)
	hgrp (.createSequentialGroup layout)
	vgrp (.createSequentialGroup layout)]
    (nru/add-hook
     :countdown
     (fn [sec max]
       (do-swing
	(.setText status "RSS取得待機中")
	(.setString pbar (format "あと %d秒" sec))
	(.setMaximum pbar max) (.setValue pbar sec))))
    (nru/add-hook
     :fetching
     (fn [fetched-count total page]
       (do-swing
	(.setEnabled rbtn false) (.setEnabled tbtn false))
	(.setText status "RSS情報取得中")
	(.setString pbar (format "%d / %d from page %d."
				 fetched-count total page))
	(.setMaximum pbar total)
	(.setValue pbar fetched-count)))
    (nru/add-hook
     :fetched
     (fn [fetched-count total]
       (do-swing (.setEnabled rbtn true) (.setEnabled tbtn true))
       (condp = total
	   0 (do ;; 通信エラーかサーバー落ちか、RSSが空っぽか。
	       (do-swing
		(.setText status "サーバーとの通信エラー")))
	   fetched-count (do ;; 全て取得できた。
		     (do-swing
		      (.setText status "RSS情報取得完了")
		      (.doClick tbtn)))
	   (do-swing
	    (.setText status "RSS情報取得中断")))))
    (doto rbtn
      (.setPreferredSize *btn-size*)
      (.setToolTipText "RSS情報の取得をすぐに開始します")
      (.setEnabled false)
      (add-action-listener (fn [e] (nru/set-counter 1))))
    (let [tstr "RSS情報の取得を開始します", pstr "RSS情報の取得を一時停止します"]
      (doto tbtn
	(.setPreferredSize *btn-size*)
	(.setToolTipText tstr)
	(add-action-listener
	 (fn [e] (if (nru/pause-rss-updator)
		   (do-swing (doto tbtn
			       (.setIcon sicn) (.setToolTipText tstr))
			     (.setEnabled rbtn false))
		   (do-swing (doto tbtn
			       (.setIcon picn) (.setToolTipText pstr))
			     (.setEnabled rbtn true)))))))
    (doto pbar
      (.setPreferredSize *bar-size*)
      (.setStringPainted true))
    (doto hgrp
      (.addGroup (.. layout createParallelGroup
		     (addComponent status)
		     (addComponent pbar)))
      (.addGroup (.. layout createParallelGroup
		     (addComponent tbtn)))
      (.addGroup (.. layout createParallelGroup
		     (addComponent rbtn))))
    (doto vgrp
      (.addGroup (.. layout createParallelGroup
		     (addGroup (.. layout createSequentialGroup
				   (addComponent status)
				   (addComponent pbar)))
		     (addComponent rbtn)
		     (addComponent tbtn))))
    (doto layout
      (.setHorizontalGroup hgrp)
      (.setVerticalGroup vgrp)
      (.setAutoCreateGaps true)
      (.setAutoCreateContainerGaps true))
    (doto rss-panel
      (.setBorder (TitledBorder. "RSS Status"))
      (.setLayout layout)
      (.add status)
      (.add pbar)
      (.add rbtn)
      (.add tbtn))))
