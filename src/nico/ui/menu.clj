;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "menu bar."}
  nico.ui.menu
  (:use [clojure.contrib.swing-utils :only [do-swing add-action-listener]]
	[nico.ui.ext-tabbed-pane :only [add-tab]])
  (:require [nico.prefs :as p]
	    [nico.ui.about-dlg :as uad]
	    [nico.ui.key-val-dlg :as ukvd]
	    [nico.ui.kwd-tab-dlg :as uktd]
	    [nico.ui.browser-dlg :as ubd])
  (:import (java.awt.event KeyEvent InputEvent WindowEvent WindowListener)
	   (javax.swing JMenuBar JMenu JMenuItem KeyStroke)))

(defn menu-bar [frame tpane]
  (let [menubar (JMenuBar.)
	fmenu (JMenu. "ファイル (F)")
	miAUT (JMenuItem. "ユーザータブの追加... (U)" KeyEvent/VK_U)
	miAKT (JMenuItem. "キーワードタブの追加... (K)" KeyEvent/VK_K)
	miExit (JMenuItem. "終了(X)" KeyEvent/VK_X)
	tmenu (JMenu. "ツール (T)")
	miBrowser (JMenuItem. "ブラウザ設定の編集... (B)" KeyEvent/VK_B)
	hmenu (JMenu. "ヘルプ (H)")
	miAbout (JMenuItem. "About..." KeyEvent/VK_A)]
    (doto miExit
      (add-action-listener
       (fn [e] (do-swing
		(.postEvent (.. frame getToolkit getSystemEventQueue)
			    (WindowEvent. frame WindowEvent/WINDOW_CLOSING)))))
      (.setAccelerator (KeyStroke/getKeyStroke KeyEvent/VK_Q InputEvent/CTRL_DOWN_MASK)))
    (doto miAUT
      (add-action-listener
       (fn [e] (let [tpref (p/gen-initial-user-tpref)
		     dlg (ukvd/user-password-dialog
			  frame "ユーザー情報の入力" tpref
			  (fn [email passwd]
			    (add-tab tpane (assoc tpref :email email :passwd passwd))))]
		 (do-swing (.setVisible dlg true))))))
    (doto miAKT
      (add-action-listener
       (fn [e] (let [tpref (p/gen-initial-keyword-tpref)
		     dlg (uktd/keyword-tab-dialog
			  frame "番組検索条件の入力" tpref
			  (fn [ntpref] (add-tab tpane ntpref)))]
		 (do-swing (.setVisible dlg true))))))
    (doto miBrowser
      (add-action-listener
       (fn [e] (let [dlg (ubd/browsers-dialog
			  frame "ブラウザ設定" (:browsers @(p/get-pref))
			  (fn [nbpref] (swap! (p/get-pref) assoc :browsers nbpref)))]
		 (do-swing (.setVisible dlg true))))))
    (doto miAbout
      (add-action-listener
       (fn [e] (let [dlg (uad/about-dialog
			  frame "About this software")]
		 (do-swing (.setVisible dlg true))))))
    (doto fmenu (.setMnemonic KeyEvent/VK_F)
      (.add miAUT)
      (.add miAKT)
      (.add miExit))
    (doto tmenu (.setMnemonic KeyEvent/VK_T)
      (.add miBrowser))
    (doto hmenu (.setMnemonic KeyEvent/VK_H)
      (.add miAbout))
    (doto menubar
      (.add fmenu)
      (.add tmenu)
      (.add hmenu))))

