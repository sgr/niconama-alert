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
	   (javax.swing BorderFactory JLabel JMenuItem JPanel JPopupMenu JScrollPane SpringLayout)
	   (javax.swing.border EtchedBorder)))

(gen-class
 :name nico.ui.ProgramsPanel
 :extends javax.swing.JPanel
 :prefix "pp-"
 :constructors {[clojure.lang.IPersistentMap] []}
 :state state
 :init init
 :post-init post-init
 :methods [[setTabComponent [java.awt.Component] void]
	   [getTabTitle [] String]
	   [getPopupMenu [] javax.swing.JPopupMenu]
	   [getTabPref [] clojure.lang.IPersistentMap]
	   [updateTitle [String] void]
	   [updatePrograms [clojure.lang.IPersistentMap] void]])

(defn- get-init-title [pref]
  (condp = (:type pref)
      :all (:title pref)
      :comm "loading..."
      :kwd (:title pref)))

(defn- get-sortability [pref]
  (condp = (:type pref)
      :all false
      :comm true
      :kwd true))

(defn- get-init-fn [this]
  (let [pref (:pref @(.state this))]
    (condp = (:type pref)
	:all (fn [] [(:title pref) (fn [pgms] pgms)])
	:comm (fn []
		(if-let [as (oa/get-alert-status (oa/get-ticket (:email pref) (:passwd pref)))]
		  (do
		    (let [user-name (:user_name as) comms (apply hash-set (:comms as))]
		      [user-name
		       (fn [pgms]
			 (select-keys
			  pgms
			  (for [[id pgm] pgms :when (contains? comms (:comm_id pgm))] id)))]))
		  ["login failed" (fn [pgms]) {}]))
	:kwd (fn []
	       (let [query (eval (uktd/transq (:query pref)))]
		 [(:title pref)
		  (fn [pgms]
		    (select-keys pgms (for [[id pgm] pgms :when
					    (some #(if (% pgm) (query (% pgm)))
						  (:target pref))] id)))])))))

(defn- pp-init [pref]
  [[] (atom {:pref pref :tab nil :tbl nil :filter-fn nil :title (get-init-title pref)})])

(defn- pp-getTabTitle [this] (:title @(.state this)))

(defn- invoke-init-fn [this]
  (.start (Thread. (fn []
		     (do-swing (.setEnabled (:tbl @(.state this)) false))
		     (.updateTitle this (get-init-title (:pref @(.state this))))
		     (when-let [[title filter-fn] ((get-init-fn this))]
		       (do
			 (swap! (.state this) assoc :title title)
			 (swap! (.state this) assoc :filter-fn filter-fn)
			 (if (< 0 (count (pgm/pgms)))
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

(defn- pp-setTabComponent [this component]
  (swap! (.state this) assoc :tab component))

(defn- pp-getTabPref [this] (:pref @(.state this)))

(defn- pp-updateTitle
  [this title]
  (when-let [tab (:tab @(.state this))]
    (swap! (.state this) assoc :title title)
    (do-swing (.setText tab title))))

(defn- pp-updatePrograms
  [this pgms]
  (let [tab (:tab @(.state this)) title (:title @(.state this))]
    (if-let [filter-fn (:filter-fn @(.state this))]
      (let [npgms (filter-fn pgms)]
	;; alert programs when it is in user-added tabs.
	(when-not (= :all (-> @(.state this) :pref :type))
	  (doseq [[id npgm] npgms] (al/alert-pgm id)))
	(do-swing
	 (.setText tab (format "%s (%d)" title (count npgms)))
	 (.updateData (.getModel (:tbl @(.state this))) npgms)))
      (do-swing
       (.setText tab (format "%s (-)" title))))))

(defn- pp-getPopupMenu [this]
  (condp = (-> @(.state this) :pref :type)
      :all nil
      :comm (let [pmenu (JPopupMenu.)
 		  ritem (JMenuItem. "Re-login")
		  eitem (JMenuItem. "Edit")]
	      (doto ritem
		(add-action-listener (fn [e] (invoke-init-fn this))))
	      (doto eitem
		(add-action-listener
		 (fn [e] (let [pref (:pref @(.state this))
			       dlg (ukvd/user-password-dialog
				    (.getTopLevelAncestor this) "ユーザー情報の編集" pref
				    (fn [email passwd]
				      (let [npref (assoc pref :email email :passwd passwd)]
					(swap! (.state this) assoc :pref npref)
					(invoke-init-fn this))))]
			   (do-swing (.setVisible dlg true))))))
	      (doto pmenu
		(.add ritem)
		(.add eitem)))
      :kwd (let [pmenu (JPopupMenu.)
		 eitem (JMenuItem. "Edit")]
	     (doto eitem
	       (add-action-listener
		(fn [e] (let [dlg (uktd/keyword-tab-dialog
				   (.getTopLevelAncestor this) "番組検索設定の編集"
				   (:pref @(.state this))
				   (fn [npref]
				     (swap! (.state this) assoc :pref npref)
				     (invoke-init-fn this)))]
			  (do-swing (.setVisible dlg true))))))
	     (doto pmenu
	       (.add eitem)))))
