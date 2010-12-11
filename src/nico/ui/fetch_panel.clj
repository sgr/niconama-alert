;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "番組情報取得パネル"}
  nico.ui.fetch-panel
  (:use [clojure.contrib.swing-utils :only [do-swing add-action-listener]])
  (:require [nico.alert :as alert]
	    [nico.pgm :as pgm]
	    [nico.rss :as rss]
	    [time-utils :as tu])
  (:import (java.awt Dimension)
	   (javax.swing ImageIcon JPanel JButton JProgressBar JLabel SpringLayout GroupLayout)))

(def *timer-max* 60)
(let [counter (atom 1), timer (atom nil), latch (atom (java.util.concurrent.CountDownLatch. 1))]
  (defn- pause []
    (if (= 1 (.getCount @latch))
      (do (.countDown @latch) false)
      (do (reset! latch (java.util.concurrent.CountDownLatch. 1)) true)))
  (defn- set-counter [c] (reset! counter c))
  (defn- set-timer [periodic-fn update-fn pause-fn]
    (reset! timer
	    (Thread. (fn []
		       (try
			 (loop [max @counter]
			   (if (= 1 (.getCount @latch))
			     (do (pause-fn) (.await @latch))
			     (update-fn @counter max))
			   (if (= 0 @counter)
			     (do (set-counter (periodic-fn)) (System/gc) (recur @counter))
			     (do (Thread/sleep 1000) (set-counter (dec @counter)) (recur max))))
		       (catch Exception e (.printStackTrace e)))))))
  (defn run-timer []
    (if @timer (.start @timer))
    (alert/run-alerter)))

(def *btn-size* (Dimension. 80 30))

(defn fetch-panel
  [tpane]
  (let [fetch-panel (JPanel.)
	lpgms (JLabel. "番組数:"), vpgms (JLabel. "- / -")
	lfetched (JLabel. "最終更新:"), vfetched (JLabel. "----/--/-- --:--:--")
	lstatus (JLabel. "ステータス:"), vstatus (JLabel. "initialized.")
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
	fetch-panel-layout (SpringLayout.)]
    (letfn [(update-fn
	     [count max]
	     (do-swing
	      (.setText vstatus (format "番組情報取得まであと%d秒" count))
	      (.setMaximum pbar max) (.setValue pbar count)))
	    (pause-fn [] (do-swing (.setText vstatus "右のボタンを押すと番組情報取得を開始します")))
	    (fetch-pgms
	     []
	     (try
	       (pgm/update-old)
	       (let [[earliest fetched total]
		     (rss/get-programs-from-rss
		      (fn [cur_pgms acc total page]
			;; インクリメンタルにpgmsを更新
			(pgm/add-pgms cur_pgms)
			(.updatePgms tpane (pgm/pgms) false)
			;; 状況表示
			(do-swing
			 (.setText vstatus (format "番組情報取得中... %d / %d from page %d."
						   (+ acc (count cur_pgms)) total page))
			 (.setText vpgms (format "%d / %d" (pgm/count-pgms) total))
			 (.setText vfetched (tu/format-time-long (tu/now)))
			 (.setMaximum pbar total)
			 (.setValue pbar (+ acc (count cur_pgms))))))]
		 (condp = total
		     0 (do ;; 通信エラーかサーバー落ちか。ともかく取得は中止する。
			 (do-swing
			  (.setText vstatus "サーバーとの通信エラー.")
			  (.setText vpgms (format "%d / %d" (pgm/count-pgms) total))
			  (.setText vfetched (tu/format-time-long (tu/now)))
			  (.updatePgms tpane (pgm/pgms) true))
			 *timer-max*)
		     (count fetched) (do ;; fetchedにない番組は削除して良い
				       (pgm/rem-pgms-without fetched)
				       (do-swing
					(.setText vstatus "番組情報取得完了.")
					(.setText vpgms (format "%d / %d" (pgm/count-pgms) total))
					(.setText vfetched (tu/format-time-long (tu/now)))
					(.updatePgms tpane (pgm/pgms) true)
					(.doClick tbtn))
				       *timer-max*)
		     (let [ratio (/ (count fetched) total)]
		       ;; 90%以上取得できたときは、fetchedのうち最も早い開始時刻より後に開始された番組で、
		       ;; fetchedにないものは削除
		       (when (<= 0.9 ratio) (pgm/rem-pgms-without fetched earliest))
		       (do-swing
			(.setText vstatus "番組情報取得中断. ")
			(.setText vpgms (format "%d / %d" (pgm/count-pgms) total))
			(.setText vfetched (tu/format-time-long (tu/now)))
			(.updatePgms tpane (pgm/pgms) true))
		       (cond (= 0 (count fetched)) *timer-max*
			     (> 0.3 ratio) *timer-max*
			     (<= 0.3 ratio) (int (* *timer-max* ratio))))))
	       (catch Exception e (.printStackTrace e) *timer-max*)))
	    (periodic-fn
	     []
	     (do-swing (.setEnabled rbtn false) (.setEnabled tbtn false))
	     (let [nc (fetch-pgms)]
	       (do-swing (.setEnabled rbtn true) (.setEnabled tbtn true))
	       nc))]
      (set-timer periodic-fn update-fn pause-fn)
      (doto rbtn
	(.setPreferredSize *btn-size*)
	(.setToolTipText "番組情報の取得をすぐに開始します")
	(.setEnabled false)
	(add-action-listener (fn [e] (set-counter 1)))))
    (let [tstr "番組情報の取得を開始します", pstr "番組情報の取得を一時停止します"]
      (doto tbtn
	(.setPreferredSize *btn-size*)
	(.setToolTipText tstr)
	(add-action-listener
	 (fn [e] (if (pause)
		   (do-swing (doto tbtn (.setIcon sicn) (.setToolTipText tstr)) (.setEnabled rbtn false))
		   (do-swing (doto tbtn (.setIcon picn) (.setToolTipText pstr)) (.setEnabled rbtn true)))))))
    (doto pbar
      (.setStringPainted true))
    (doto stat-panel-hgrp
      (.addGroup (.. stat-panel-layout createParallelGroup
		     (addComponent lpgms) (addComponent lfetched)))
      (.addGroup (.. stat-panel-layout createParallelGroup
		     (addComponent vpgms) (addComponent vfetched)))
      (.addGroup (.. stat-panel-layout createParallelGroup
		     (addGroup (.. stat-panel-layout createSequentialGroup
				    (addComponent lstatus) (addComponent vstatus)))
		     (addComponent pbar))))
    (doto stat-panel-vgrp
      (.addGroup (.. stat-panel-layout createParallelGroup
		     (addComponent lpgms) (addComponent vpgms)
		     (addComponent lstatus) (addComponent vstatus)))
      (.addGroup (.. stat-panel-layout createParallelGroup
		     (addComponent lfetched) (addComponent vfetched) (addComponent pbar))))
    (doto stat-panel-layout
      (.setHorizontalGroup stat-panel-hgrp)
      (.setVerticalGroup stat-panel-vgrp)
      (.setAutoCreateGaps true)
      (.setAutoCreateContainerGaps true))
    (doto stat-panel
      (.setLayout stat-panel-layout)
      (.add lpgms)
      (.add vpgms)
      (.add lstatus)
      (.add vstatus)
      (.add lfetched)
      (.add vfetched)
      (.add pbar))
    (doto fetch-panel-layout
      (.putConstraint SpringLayout/NORTH stat-panel 1 SpringLayout/NORTH fetch-panel)
      (.putConstraint SpringLayout/SOUTH stat-panel -1 SpringLayout/SOUTH fetch-panel)
      (.putConstraint SpringLayout/NORTH tbtn 5 SpringLayout/NORTH fetch-panel)
      (.putConstraint SpringLayout/SOUTH tbtn -5 SpringLayout/SOUTH fetch-panel)
      (.putConstraint SpringLayout/NORTH rbtn 5 SpringLayout/NORTH fetch-panel)
      (.putConstraint SpringLayout/SOUTH rbtn -5 SpringLayout/SOUTH fetch-panel)
      (.putConstraint SpringLayout/WEST stat-panel 1 SpringLayout/WEST fetch-panel)
      (.putConstraint SpringLayout/EAST stat-panel -5 SpringLayout/WEST tbtn)
      (.putConstraint SpringLayout/EAST tbtn -5 SpringLayout/WEST rbtn)
      (.putConstraint SpringLayout/EAST rbtn -5 SpringLayout/EAST fetch-panel))
    (doto fetch-panel
      (.setPreferredSize (Dimension. 500 60))
      (.setLayout fetch-panel-layout)
      (.add stat-panel)
      (.add rbtn)
      (.add tbtn))))

