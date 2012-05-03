;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "menu bar."}
  nico.ui.menu
  (:use [clojure.tools.swing-utils :only [do-swing add-action-listener]]
	[nico.ui.ext-tabbed-pane :only [add-tab]])
  (:require [nico.prefs :as p]
	    [nico.ui.about-dlg :as uad]
	    [nico.ui.key-val-dlg :as ukvd]
	    [nico.ui.kwd-tab-dlg :as uktd]
	    [nico.ui.browser-dlg :as ubd])
  (:import [java.awt Desktop]
	   [java.awt.event KeyEvent InputEvent WindowEvent WindowListener]
	   [java.net URI]
	   [javax.swing JMenuBar JMenu JMenuItem JSeparator KeyStroke]
	   [javax.swing.event MenuListener]))

(def ^{:private true} HELP-URL "https://github.com/sgr/niconama-alert/wiki/Help")

(defn menu-bar [frame tpane]
  (let [menubar (JMenuBar.)
	fmenu (JMenu. "ファイル (F)")
	miBrowser (JMenuItem. "ブラウザ設定の編集... (B)" KeyEvent/VK_B)
	miExit (JMenuItem. "終了(X)" KeyEvent/VK_X)
	tmenu (JMenu. "タブ (T)")
	miAUT (JMenuItem. "ユーザータブの追加... (U)" KeyEvent/VK_U)
	miAKT (JMenuItem. "キーワードタブの追加... (K)" KeyEvent/VK_K)
	hmenu (JMenu. "ヘルプ (H)")
	miHelp (JMenuItem. "ヘルプページの参照 (H)" KeyEvent/VK_H)
	miAbout (JMenuItem. "About..." KeyEvent/VK_A)]
    (doto miBrowser
      (add-action-listener
       (fn [e] (let [dlg (ubd/browsers-dialog
			  frame "ブラウザ設定" (:browsers @(p/get-pref))
			  (fn [nbpref] (swap! (p/get-pref) assoc :browsers nbpref)))]
		 (do-swing (.setVisible dlg true))))))
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
			  (fn [ntpref] (add-tab tpane ntpref)))]
		 (do-swing (.setVisible dlg true))))))
    (doto miAKT
      (add-action-listener
       (fn [e] (let [tpref (p/gen-initial-keyword-tpref)
		     dlg (uktd/keyword-tab-dialog
			  frame "番組検索条件の入力" tpref
			  (fn [ntpref] (add-tab tpane ntpref)))]
		 (do-swing (.setVisible dlg true))))))
    (doto miHelp
      (add-action-listener
       (fn [e] (.browse (Desktop/getDesktop) (URI. HELP-URL))))
      (.setAccelerator (KeyStroke/getKeyStroke KeyEvent/VK_F1 0)))
    (doto miAbout
      (add-action-listener
       (fn [e] (let [dlg (uad/about-dialog
			  frame "About this software")]
		 (do-swing (.setVisible dlg true))))))
    (doto fmenu
      (.setMnemonic KeyEvent/VK_F)
      (.add miBrowser)
      (.add miExit))
    (doto tmenu
      (.setMnemonic KeyEvent/VK_T)
      (.add miAUT)
      (.add miAKT)
      (.addMenuListener
       (proxy [MenuListener] []
	 (menuSelected
	  [e]
	  (when-let [items (.getTabMenuItems tpane (.getSelectedIndex tpane))]
	    (.add tmenu (JSeparator.))
	    (doseq [item items] (.add tmenu item))))
	 (menuDeselected
	  [e]
	  (doseq [idx (range (dec (.getItemCount tmenu)) 1 -1)]
	    (.remove tmenu idx)))
	 (menuCanceled [e]))))
    (doto hmenu
      (.setMnemonic KeyEvent/VK_H)
      (.add miHelp)
      (.add miAbout))
    (doto menubar
      (.add fmenu)
      (.add tmenu)
      (.add hmenu))))

