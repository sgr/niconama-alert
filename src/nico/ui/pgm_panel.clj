;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "番組リスト表示パネル"}
  nico.ui.pgm-panel
  (:use [clojure.contrib.swing-utils :only [do-swing add-action-listener]])
  (:require [nico.official-alert :as oa]
	    [nico.ui.pgm-table :as upt]
	    [nico.ui.key-val-dlg :as ukvd]
	    [nico.ui.kwd-tab-dlg :as uktd]
	    [nico.alert :as al]
	    [nico.pgm :as pgm])
  (:import (java.awt Dimension)
	   (javax.swing BorderFactory JCheckBoxMenuItem JLabel JMenuItem
			JPanel JScrollPane SpringLayout)
	   (javax.swing.border EtchedBorder)))

(gen-class
 :name nico.ui.ProgramsPanel
 :extends javax.swing.JPanel
 :prefix "pp-"
 :constructors {[clojure.lang.IPersistentMap] []}
 :state state
 :init init
 :post-init post-init
 :methods [[getTitleLabel [] javax.swing.JLabel]
	   [getTabMenuItems [] clojure.lang.IPersistentVector]
	   [getTabPref [] clojure.lang.IPersistentMap]
	   [setTabPref [clojure.lang.IPersistentMap] void]
	   [updateTitle [String] void]
	   [updatePrograms [clojure.lang.IPersistentMap] void]])

(defn- get-init-title [pref]
  (condp = (:type pref) :all (:title pref) :comm "loading..." :kwd (:title pref)))

(defn- get-sortability [pref]
  (condp = (:type pref) :all false :comm true :kwd true))

(defn- get-init-fn [this]
  (let [pref (:pref @(.state this))]
    (condp = (:type pref)
	:all (fn [] [(:title pref) (fn [pgms] [(pgm/count-pgms) pgms])])
	:comm (fn []
		(if-let [as (oa/get-alert-status (:email pref) (:passwd pref))]
		  (do
		    (let [user-name (:user_name as) comms (apply hash-set (:comms as))]
		      [user-name
		       (fn [pgms]
			 (let [npgms
			       (select-keys
				pgms
				(for [[id pgm] pgms :when (contains? comms (:comm_id pgm))] id))]
			   [(count npgms) npgms]))]))
		  ["login failed" (fn [pgms]) [0 {}]]))
	:kwd (fn []
	       (let [query (eval (uktd/transq (:query pref)))]
		 [(:title pref)
		  (fn [pgms]
		    (let [npgms (select-keys pgms
					     (for [[id pgm] pgms :when
						   (query
						    (reduce #(str %1 " " %2)
							    (map #(% pgm) (:target pref))))] id))]
		      [(count npgms) npgms]))])))))

(defn- pp-init [pref]
  (let [title (get-init-title pref)]
    [[] (atom {:pref pref :tbl nil :filter-fn nil
	       :title title :title-label (JLabel. title)})]))

(defn- pp-getTitleLabel [this] (:title-label @(.state this)))

(defn- invoke-init-fn [this]
  (.start (Thread. (fn []
		     (do-swing (.setEnabled (:tbl @(.state this)) false))
		     (.updateTitle this (get-init-title (:pref @(.state this))))
		     (when-let [[title filter-fn] ((get-init-fn this))]
		       (do
			 (swap! (.state this) assoc :title title)
			 (swap! (.state this) assoc :filter-fn filter-fn)
			 (if (< 0 (pgm/count-pgms))
			   (.updatePrograms this (pgm/pgms))
			   (.updateTitle this title))
			 (do-swing (.setEnabled (:tbl @(.state this)) true))))))))

(defn- pp-post-init [this pref]
  (let [tbl (upt/pgm-table)
	spane (JScrollPane. tbl)
	layout (SpringLayout.)]
    (doto tbl (.setSortable (get-sortability pref)) (.setEnabled false))
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
  [this pgms]
  (let [tlabel (:title-label @(.state this)) title (:title @(.state this))]
    (if-let [filter-fn (:filter-fn @(.state this))]
      (let [[cnt npgms] (filter-fn pgms)]
	;; alert programs.
	(.start (Thread.
		 (fn [] 
		   (when (-> @(.state this) :pref :alert)
		     (doseq [[id npgm] npgms] (al/alert-pgm id))))))
	(do-swing
	 (.setText tlabel (format "%s (%d)" title cnt))
	 (.updateData (.getModel (:tbl @(.state this))) npgms)))
      (do-swing
       (.setText tlabel (format "%s (-)" title))))))

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
				    (fn [email passwd]
				      (let [npref (assoc pref :email email :passwd passwd)]
					(.setTabPref this npref))))]
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
				   (fn [npref]
				     (.setTabPref this npref)))]
			  (do-swing (.setVisible dlg true))))))
	      (doto aitem
		(add-action-listener
		 (fn [e] (let [pref (:pref @(.state this))
			       val (.isSelected aitem)]
			   (swap! (.state this) assoc :pref (assoc pref :alert val))))))
	      [aitem eitem])))

