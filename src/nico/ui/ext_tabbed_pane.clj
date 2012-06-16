;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "タブに閉じるボタンと種別アイコンがついたJTabbedPane"}
  nico.ui.ext-tabbed-pane
  (:use [clojure.tools.swing-utils :only [do-swing-and-wait add-action-listener]])
  (:require [clojure.java.jdbc :as jdbc]
            [time-utils :as tu]
	    [nico.pgm :as pgm]
	    [nico.ui.pgm-panel])
  (:import [java.awt BorderLayout Dimension]
	   [java.awt.event MouseListener]
           [java.sql PreparedStatement]
	   [javax.swing BorderFactory ImageIcon JButton JLabel JMenuItem
			JOptionPane JPanel JPopupMenu SwingUtilities]))

(def ^{:private true} COMM-TAB-ICON  (ImageIcon. (.getResource (.getClassLoader (class (fn []))) "usrtab.png")))
(def ^{:private true} KWD-TAB-ICON   (ImageIcon. (.getResource (.getClassLoader (class (fn []))) "kwdtab.png")))
(def ^{:private true} CLOSE-BTN-ICON (ImageIcon. (.getResource (.getClassLoader (class (fn []))) "closebtn.png")))

(gen-class
 :name nico.ui.ExtTabbedPane
 :extends javax.swing.JTabbedPane
 :prefix "etp-"
 :constructors {[javax.swing.Icon javax.swing.Icon javax.swing.Icon] []}
 :state state
 :init init
 :post-init post-init
 :methods [[addExtTab [clojure.lang.Keyword java.awt.Component] void]
           [setQuery [nico.ui.ProgramsPanel String] void]
           [remQuery [nico.ui.ProgramsPanel] void]
           [closeConn [] void]
           [getPgms [nico.ui.ProgramsPanel] clojure.lang.IPersistentMap]
	   [updatePgms [boolean] void]
	   [getTabMenuItems [int] clojure.lang.IPersistentVector]
	   [getTabPrefs [] clojure.lang.IPersistentVector]])

(defn ext-tabbed-pane []
  (let [cloader (.getClassLoader (class (fn [])))]
    (nico.ui.ExtTabbedPane. COMM-TAB-ICON KWD-TAB-ICON CLOSE-BTN-ICON)))

(defn- etp-init [commicn kwdicn closeicn]
  [[] (atom {:last-updated nil :conn (delay (pgm/get-ro-conn)) :pstmts {}})])

(defn- etp-setQuery [this tab sql]
  (let [id-tab (.hashCode tab) conn @(:conn @(.state this))]
    (when-let [old-pstmt (get-in @(.state this) [:pstmts id-tab])] (.close old-pstmt))
    (when conn
      (let [pstmt (jdbc/prepare-statement conn sql :concurrency :read-only)]
        (swap! (.state this) assoc-in [:pstmts id-tab] pstmt)))))

(defn- etp-remQuery [this tab]
  (let [id-tab (.hashCode tab)]
    (when-let [pstmt (get-in @(.state this) [:pstmts id-tab])]
      (.close pstmt)
      (let [pstmts (:pstmts @(.state this))]
        (swap! (.state this) assoc :pstmts (dissoc pstmts id-tab))))))

(defn- etp-closeConn [this]
  (doseq [pstmt (vals (:pstmts @(.state this)))] (.close pstmt))
  (swap! (.state this) assoc :pstmts {})
  (when-let [conn @(:conn @(.state this))] (.close conn)))

(defn- etp-getPgms [this tab]
  (if-let [pstmt (get-in @(.state this) [:pstmts (.hashCode tab)])]
    (pgm/search-pgms-by-pstmt pstmt)
    {}))

(defn- confirm-rem-tab-fn [tabbed-pane content]
  (fn [e]
    (when (= JOptionPane/OK_OPTION
	     (JOptionPane/showConfirmDialog
	      tabbed-pane "タブを削除しますか？" "削除確認"
	      JOptionPane/OK_CANCEL_OPTION JOptionPane/WARNING_MESSAGE))
      (.removeTabAt tabbed-pane (.indexOfComponent tabbed-pane content))
      (.remQuery tabbed-pane content)))) ; preparedStatementを閉じる

(defn- etp-tab-panel [this kind content]
  (if (= kind :all)
    (let [tab (JPanel. (BorderLayout.))
	  ltitle (.getTitleLabel content)]
      (doto ltitle (.setBorder (BorderFactory/createEmptyBorder 0 4 0 4)))
      (doto tab
	(.setOpaque false)
	(.add ltitle BorderLayout/CENTER)
	(.setBorder (BorderFactory/createEmptyBorder 2 0 0 0))))
    (let [ticn (condp = kind :comm COMM-TAB-ICON :kwd KWD-TAB-ICON)
	  cicn CLOSE-BTN-ICON
	  lticn (JLabel. ticn)
	  tab (JPanel. (BorderLayout.))
	  ltitle (.getTitleLabel content)
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
	(.setBorder (BorderFactory/createEmptyBorder 3 0 2 0))))))

(defn- etp-getTabMenuItems [this idx]
  (when-not (= -1 idx)
    (let [content (.getComponentAt this idx)]
      (when-let [itms (.getTabMenuItems content)]
	(let [items (atom itms)]
	  (letfn [(swap-fn
		   [idx nidx]
		   (fn [e]
		     (let [type (:type (.getTabPref content))]
		       (doto this
			 (.remove idx)
			 (.insertTab nil nil content nil nidx)
			 (.setTabComponentAt nidx (etp-tab-panel this type content))
			 (.setSelectedIndex nidx)))))]
	    (when (> (dec (.getTabCount this)) idx)
	      (swap! items conj
		     (doto (JMenuItem. "右へ") (add-action-listener (swap-fn idx (inc idx))))))
	    (when (< 1 idx)
	      (swap! items conj
		     (doto (JMenuItem. "左へ") (add-action-listener (swap-fn idx (dec idx))))))
	    (conj @items
		  (doto (JMenuItem. "削除")
		    (add-action-listener (confirm-rem-tab-fn this content))))))))))

(defn- etp-post-init [this commicn kwdicn closeicn]
  (let [tpane this]
    (doto tpane
      (.addMouseListener
       (proxy [MouseListener] []
	 (mouseClicked
	  [e]
	  (when (SwingUtilities/isRightMouseButton e)
	    (let [x (.getX e) y (.getY e) idx (.indexAtLocation tpane x y)]
	      (when-let [items (.getTabMenuItems tpane idx)]
		(let [pmenu (JPopupMenu.)]
		  (doseq [item items] (doto pmenu (.add item)))
		  (.show pmenu tpane (.getX e) (.getY e)))))))
	 (mouseEntered [e])
	 (mouseExited [e])
	 (mousePressed [e])
	 (mouseReleased [e]))))
    (pgm/add-pgms-hook :updated (fn [] (.updatePgms tpane true))))
  (pgm/add-db-hook :shutdown (fn [] (.closeConn this))))
  
(defn- etp-addExtTab [this kind content]
  (doto this
    (.addTab nil content)
    (.setTabComponentAt (.indexOfComponent this content) (etp-tab-panel this kind content))))

(defn- etp-update-pgms [this]
  (doseq [idx (range (.getTabCount this))] (.updatePrograms (.getComponentAt this idx))))

(defn- etp-updatePgms [this enforce]
  (let [last-updated (:last-updated @(.state this))]
    (when (or enforce
	      (nil? last-updated)
	      (not (tu/within? last-updated (tu/now) 3)))
      (do (etp-update-pgms this)
	  (swap! (.state this) assoc :last-updated (tu/now))))))

(defn- etp-getTabPrefs [this]
  (vec (map #(.getTabPref (.getComponentAt this %)) (range (.getTabCount this)))))

(defn add-tab [tpane tpref]
  (do-swing-and-wait (.addExtTab tpane (:type tpref) (nico.ui.ProgramsPanel. tpane tpref))))

