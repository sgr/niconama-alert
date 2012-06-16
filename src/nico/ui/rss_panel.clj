;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "番組情報取得パネル"}
  nico.ui.rss-panel
  (:use [clojure.tools.swing-utils :only [do-swing add-action-listener]])
  (:require [nico.rss-updator :as nru])
  (:import [java.awt Dimension]
	   [javax.swing GroupLayout ImageIcon JPanel JButton JProgressBar JLabel]
	   [javax.swing.border TitledBorder]))

(def ^{:private true} BTN-SIZE (Dimension. 80 30))
(def ^{:private true} BAR-SIZE (Dimension. 200 20))

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
    (nru/add-rss-hook
     :countdown
     (fn [sec max]
       (do-swing
	(.setText status "RSS取得待機中")
	(.setString pbar (format "あと %d秒" sec))
	(.setMaximum pbar max) (.setValue pbar sec))))
    (nru/add-rss-hook
     :fetching
     (fn [fetched-count total page]
       (do-swing
	(.setEnabled rbtn false) (.setEnabled tbtn false))
	(.setText status "RSS情報取得中")
	(.setString pbar (format "%d / %d from page %d."
				 fetched-count total page))
	(.setMaximum pbar total)
	(.setValue pbar fetched-count)))
    (nru/add-rss-hook
     :fetched
     (fn [fetched-count total]
       (do-swing (.setEnabled rbtn true) (.setEnabled tbtn true))
       (condp = total
	   0 (do-swing (.setText status "サーバーとの通信エラー"))
	   fetched-count (do-swing (.setText status "RSS情報取得完了"))
	   (do-swing (.setText status "RSS情報取得中断")))))
    (doto rbtn
      (.setPreferredSize BTN-SIZE)
      (.setToolTipText "RSS情報の取得をすぐに開始します")
      (.setEnabled false)
      (add-action-listener (fn [e] (nru/set-counter 1))))
    (let [tstr "RSS情報の取得を開始します", pstr "RSS情報の取得を一時停止します"]
      (doto tbtn
	(.setPreferredSize BTN-SIZE)
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
      (.setPreferredSize BAR-SIZE)
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

