;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "番組リスト表示パネル"}
  nico.ui.pgm-panel
  (:use [clojure.tools.swing-utils :only [do-swing add-action-listener]]
	[clojure.tools.logging])
  (:require [log-utils :as l]
            [nico.api :as api]
	    [nico.ui.pgm-table :as upt]
	    [nico.ui.key-val-dlg :as ukvd]
	    [nico.ui.kwd-tab-dlg :as uktd]
	    [nico.alert :as al]
	    [nico.pgm :as pgm]
	    [nico.api-updator :as nau])
  (:import [java.awt Color Dimension]
	   [javax.swing BorderFactory JCheckBoxMenuItem JLabel JMenuItem
			JPanel JScrollPane JTabbedPane SpringLayout]
	   [javax.swing.border EtchedBorder]))

(gen-class
 :name nico.ui.ProgramsPanel
 :extends javax.swing.JPanel
 :prefix "pp-"
 :constructors {[javax.swing.JTabbedPane clojure.lang.IPersistentMap] []}
 :state state
 :init init
 :post-init post-init
 :methods [[getTitleLabel [] javax.swing.JLabel]
	   [getTabMenuItems [] clojure.lang.IPersistentVector]
	   [getTabPref [] clojure.lang.IPersistentMap]
	   [setTabPref [clojure.lang.IPersistentMap] void]
	   [updateTitle [String] void]
	   [updatePrograms [] void]])

(defn- get-init-title [pref]
  (condp = (:type pref) :all (:title pref) :comm "loading..." :kwd (:title pref)))

(defn- get-sortability [pref]
  (condp = (:type pref) :all false :comm true :kwd true))

(defn- get-init-fn [this]
  (let [pref (:pref @(.state this))]
    (condp = (:type pref)
	:all (fn [] [(:title pref) nil])
	:comm (fn []
		(if-let [as (api/get-alert-status (:email pref) (:passwd pref))]
		  (do
		    (nau/add-alert-status as)
		    (let [user-name (:user_name as) comms (apply hash-set (:comms as))]
		      [user-name (pgm/get-sql-comm-id comms)]))
		  ["login failed" nil nil]))
	:kwd (fn []
               [(:title pref) (pgm/get-sql-kwds (:query pref) (:target pref))]))))

(defn- pp-init [tpane pref]
  (let [title (get-init-title pref)]
    [[] (atom {:tpane tpane :pref pref :tbl nil :sql nil
	       :title title :title-label (JLabel. title)})]))

(defn- pp-getTitleLabel [this] (:title-label @(.state this)))

(defn- invoke-init-fn [this]
  (future
    (do-swing (.setEnabled (:tbl @(.state this)) false))
    (.updateTitle this (get-init-title (:pref @(.state this))))
    (when-let [[title sql] ((get-init-fn this))]
      (debug (format "title: %s, sql: %s" title sql))
      (swap! (.state this) assoc :title title :sql sql)
      (when sql (.setQuery (:tpane @(.state this)) this sql))
      (if (< 0 (pgm/count-pgms))
        (.updatePrograms this)
        (.updateTitle this title))
      (do-swing (.setEnabled (:tbl @(.state this)) true)))))

(defn- pp-post-init [this tpane pref]
  (let [tbl (doto (upt/pgm-table) (.setSortable (get-sortability pref)) (.setEnabled false))
	spane (doto (JScrollPane. tbl) (-> .getViewport (.setBackground Color/WHITE)))
	layout (SpringLayout.)]
    (swap! (.state this) assoc :tbl tbl)
    ;; init-fnを実行し、終了したところでtblを有効化する。
    (invoke-init-fn this)
    (doto layout
      (.putConstraint SpringLayout/WEST spane 5 SpringLayout/WEST this)
      (.putConstraint SpringLayout/EAST spane -5 SpringLayout/EAST this)
      (.putConstraint SpringLayout/NORTH spane 5 SpringLayout/NORTH this)
      (.putConstraint SpringLayout/SOUTH spane -5 SpringLayout/SOUTH this))
    (doto this
      (.setLayout layout)
      (.add spane))))

(defn- pp-getTabPref [this] (:pref @(.state this)))

(defn- pp-setTabPref [this pref]
  (swap! (.state this) assoc :pref pref)
  (invoke-init-fn this))

(defn- pp-updateTitle
  [this title]
  (when-let [tlabel (:title-label @(.state this))]
    (swap! (.state this) assoc :title title)
    (do-swing (.setText tlabel title))))

(defn- pp-updatePrograms
  [this]
  (let [tlabel (:title-label @(.state this)) title (:title @(.state this))]
    (try
      (if (:sql @(.state this))
        (let [npgms (.getPgms (:tpane @(.state this)) this)]
          ;; alert programs.
          (when (-> @(.state this) :pref :alert)
            (future
              (doseq [[id npgm] npgms] (al/alert-pgm id))))
          (do-swing
            (.setText tlabel (format "%s (%d)" title (count npgms)))
            (.updateData (.getModel (:tbl @(.state this))) npgms)))
        (do-swing
          (.setText tlabel (format "%s (-)" title))))
      (catch Exception e
        (error e "failed update programs")))))

(defn- pp-getTabMenuItems [this]
  (condp = (-> @(.state this) :pref :type)
      :all nil
      :comm (let [aitem (JCheckBoxMenuItem. "アラート" (-> @(.state this) :pref :alert))
 		  ritem (JMenuItem. "再ログイン")
		  eitem (JMenuItem. "編集")]
	      (doto ritem
		(add-action-listener (fn [e] (invoke-init-fn this))))
	      (doto eitem
		(add-action-listener
		 (fn [e] (let [pref (:pref @(.state this))
			       dlg (ukvd/user-password-dialog
				    (.getTopLevelAncestor this) "ユーザー情報の編集" pref
				    (fn [npref] (.setTabPref this npref)))]
			   (do-swing (.setVisible dlg true))))))
	      (doto aitem
		(add-action-listener
		 (fn [e] (let [pref (:pref @(.state this))
			       val (.isSelected aitem)]
			   (swap! (.state this) assoc :pref (assoc pref :alert val))))))
	      [aitem ritem eitem])
      :kwd (let [aitem (JCheckBoxMenuItem. "アラート" (-> @(.state this) :pref :alert))
		 eitem (JMenuItem. "編集")]
	     (doto eitem
	       (add-action-listener
		(fn [e] (let [dlg (uktd/keyword-tab-dialog
				   (.getTopLevelAncestor this) "番組検索設定の編集"
				   (:pref @(.state this))
				   (fn [npref] (.setTabPref this npref)))]
			  (do-swing (.setVisible dlg true))))))
	      (doto aitem
		(add-action-listener
		 (fn [e] (let [pref (:pref @(.state this))
			       val (.isSelected aitem)]
			   (swap! (.state this) assoc :pref (assoc pref :alert val))))))
	      [aitem eitem])))

