;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "タブに閉じるボタンと種別アイコンがついたJTabbedPane"}
  nico.ui.ext-tabbed-pane
  (:use [clojure.contrib.swing-utils :only [do-swing do-swing* add-action-listener]])
  (:require [time-utils :as tu]
	    [nico.pgm :as pgm]
	    [nico.ui.fetch-panel :as ufp]
	    [nico.ui.pgm-panel])
  (:import (java.awt BorderLayout Dimension)
	   (java.awt.event MouseListener)
	   (javax.swing BorderFactory ImageIcon JButton JLabel JMenuItem
			JOptionPane JPanel SwingUtilities)))

(gen-class
 :name nico.ui.ExtTabbedPane
 :extends javax.swing.JTabbedPane
 :prefix "etp-"
 :constructors {[javax.swing.Icon javax.swing.Icon javax.swing.Icon] []}
 :state state
 :init init
 :post-init post-init
 :methods [[addExtTab [clojure.lang.Keyword java.awt.Component] void]
	   [updateTabs [clojure.lang.IPersistentMap boolean] void]
	   [getTabPrefs [] clojure.lang.LazySeq]])

(defn ext-tabbed-pane []
  (let [cloader (.getClassLoader (class (fn [])))]
    (nico.ui.ExtTabbedPane.
     (ImageIcon. (.getResource cloader "usrtab.png"))
     (ImageIcon. (.getResource cloader "kwdtab.png"))
     (ImageIcon. (.getResource cloader "closebtn.png")))))

(defn- etp-init [commicn kwdicn closeicn]
  [[] (atom {:comm commicn :kwd kwdicn :close closeicn :last-updated nil})])

(defn- confirm-rem-tab-fn [tabbed-pane content]
  (fn [e]
    (when (= JOptionPane/OK_OPTION
	     (JOptionPane/showConfirmDialog
	      tabbed-pane "タブを削除しますか？" "削除確認"
	      JOptionPane/OK_CANCEL_OPTION JOptionPane/WARNING_MESSAGE))
      (.removeTabAt tabbed-pane (.indexOfComponent tabbed-pane content)))))

(defn- etp-post-init [this commicn kwdicn closeicn]
  (let [tpane this]
    (.addMouseListener tpane
		       (proxy [MouseListener] []
			 (mouseClicked
			  [e]
			  (when (SwingUtilities/isRightMouseButton e)
			    (let [x (.getX e) y (.getY e) idx (.indexAtLocation tpane x y)]
			      (when-not (= -1 idx)
				(let [content (.getComponentAt tpane idx)]
				  (if-let [pmenu (.getPopupMenu content)]
				    (let [ditem (JMenuItem. "Delete")]
				      (doto ditem
					(add-action-listener (confirm-rem-tab-fn tpane content)))
				      (doto pmenu
					(.add ditem)
					(.show tpane (.getX e) (.getY e))))))))))
			 (mouseEntered [e])
			 (mouseExited [e])
			 (mousePressed [e])
			 (mouseReleased [e])))))
  
(defn- etp-tab-panel [this kind content]
  (if (= kind :all)
    (let [tab (JPanel. (BorderLayout.))
	  ltitle (JLabel. (.getTabTitle content))]
      (doto ltitle (.setBorder (BorderFactory/createEmptyBorder 0 4 0 4)))
      (doto tab
	(.setOpaque false)
	(.add ltitle BorderLayout/CENTER)
	(.setBorder (BorderFactory/createEmptyBorder 2 0 0 0)))
      [ltitle tab])
    (let [ticn (kind @(.state this))
	  cicn (:close @(.state this))
	  lticn (JLabel. ticn)
	  tab (JPanel. (BorderLayout.))
	  ltitle (JLabel. (.getTabTitle content))
	  cbtn (JButton. cicn)]
      (doto ltitle (.setBorder (BorderFactory/createEmptyBorder 0 4 0 10)))
      (doto lticn
	(.setPreferredSize (Dimension. (.getIconWidth ticn) (.getIconHeight ticn))))
      (doto cbtn
	(add-action-listener (confirm-rem-tab-fn this content))
	(.setPreferredSize (Dimension. (.getIconWidth cicn) (.getIconHeight cicn))))
      (doto tab
	(.setOpaque false)
	(.add lticn BorderLayout/WEST)
	(.add ltitle BorderLayout/CENTER)
	(.add cbtn BorderLayout/EAST)
	(.setBorder (BorderFactory/createEmptyBorder 3 0 2 0)))
      [ltitle tab])))

(defn- etp-addExtTab [this kind content]
  (let [[ltitle tab] (etp-tab-panel this kind content)]
    (.addTab this nil content)
    (.setTabComponent content ltitle)
    (.setTabComponentAt this (.indexOfComponent this content) tab)))

(defn- etp-update-tabs [this pgms]
  (doseq [idx (range (.getTabCount this))] (.updatePrograms (.getComponentAt this idx) pgms)))

(defn- etp-updateTabs [this pgms enforce]
  (let [last-updated (:last-updated @(.state this))]
    (when (or enforce
	      (nil? last-updated)
	      (< 3000 (- (.getTime (tu/now)) (.getTime last-updated))))
      (do (etp-update-tabs this pgms)
	  (swap! (.state this) assoc :last-updated (tu/now))))))

(defn- etp-getTabPrefs [this]
  (map #(.getTabPref (.getComponentAt this %)) (range 1 (.getTabCount this))))

(defn add-all-tab
  "放送中のすべての番組情報を表示するタブを追加する。
   現時点で総番組数は3000以上にもなるため、テーブルのソート機能はオフにする"
  [tpane]
  (do-swing* :now #(.addExtTab tpane :all (nico.ui.ProgramsPanel. {:type :all :title "All"}))))

(defn add-tab [tpane tpref]
  (do-swing* :now #(.addExtTab tpane (:type tpref) (nico.ui.ProgramsPanel. tpref))))
