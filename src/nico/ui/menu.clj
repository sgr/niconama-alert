;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "menu bar."}
  nico.ui.menu
  (:use [clojure.tools.swing-utils :only [do-swing add-action-listener make-action make-menubar]]
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
  (make-menubar
   [{:name "ファイル (F)" :mnemonic KeyEvent/VK_F
     :items [{:action (make-action
                       {:name "ブラウザ設定の編集... (B)" :mnemonic KeyEvent/VK_B
                        :handler (fn [e]
                                   (let [dlg (ubd/browsers-dialog
                                              frame "ブラウザ設定" (:browsers @(p/get-pref))
                                              (fn [nbpref] (swap! (p/get-pref) assoc :browsers nbpref)))]
                                     (do-swing (.setVisible dlg true))))})}
             {:action (make-action
                       {:name "終了(X)" :mnemonic KeyEvent/VK_X
                        :accelerator (KeyStroke/getKeyStroke KeyEvent/VK_Q InputEvent/CTRL_DOWN_MASK)
                        :handler (fn [e]
                                   (do-swing
                                    (.postEvent (.. frame getToolkit getSystemEventQueue)
                                                (WindowEvent. frame WindowEvent/WINDOW_CLOSING))))})}]}
    {:name "タブ (T)" :mnemonic KeyEvent/VK_T
     :listener (proxy [MenuListener] []
                 (menuSelected
                   [e]
                   (when-let [items (.getTabMenuItems tpane (.getSelectedIndex tpane))]
                     (let [tmenu (.getSource e)]
                       (.add tmenu (JSeparator.))
                       (doseq [item items] (.add tmenu item)))))
                 (menuDeselected
                   [e]
                   (let [tmenu (.getSource e)]
                     (doseq [idx (range (dec (.getItemCount tmenu)) 1 -1)]
                       (.remove tmenu idx))))
                 (menuCanceled [e]))
     :items [{:action (make-action
                       {:name "ユーザータブの追加... (U)" :mnemonic KeyEvent/VK_U
                        :handler (fn [e] (let [tpref (p/gen-initial-user-tpref)
                                               dlg (ukvd/user-password-dialog
                                                    frame "ユーザー情報の入力" tpref
                                                    (fn [ntpref] (add-tab tpane ntpref)))]
                                           (do-swing (.setVisible dlg true))))})}
             {:action (make-action
                       {:name "キーワードタブの追加... (K)" :mnemonic KeyEvent/VK_K
                        :handler (fn [e] (let [tpref (p/gen-initial-keyword-tpref)
                                               dlg (uktd/keyword-tab-dialog
                                                    frame "番組検索条件の入力" tpref
                                                    (fn [ntpref] (add-tab tpane ntpref)))]
                                           (do-swing (.setVisible dlg true))))})}]}
    {:name "ヘルプ (H)"
     :items [{:action (make-action
                       {:name "ヘルプページの参照 (H)" :mnemonic KeyEvent/VK_H
                        :accelerator (KeyStroke/getKeyStroke KeyEvent/VK_F1 0)
                        :handler (fn [e] (.browse (Desktop/getDesktop) (URI. HELP-URL)))})}
             {:action (make-action
                       {:name "About..." :mnemonic KeyEvent/VK_A
                        :handler (fn [e] (let [dlg (uad/about-dialog
                                                    frame "About this software")]
                                           (do-swing (.setVisible dlg true))))})}]}
    ]))


