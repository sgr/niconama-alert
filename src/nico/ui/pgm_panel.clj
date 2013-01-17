;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "番組リスト表示パネル"}
  nico.ui.pgm-panel
  (:use [clojure.tools.swing-utils :only [do-swing add-action-listener]])
  (:require [nico.ui.pgm-table :as upt])
  (:import [java.awt Color]
	   [javax.swing JPanel JScrollPane SpringLayout]))

(gen-class
 :name nico.ui.ProgramsPanel
 :extends javax.swing.JPanel
 :prefix "pp-"
 :constructors {[] []}
 :state state
 :init init
 :post-init post-init
 :methods [[repaintTable [] void]
           [getPgms [] clojure.lang.IPersistentMap]
           [setPgms [clojure.lang.IPersistentMap] void]])

(defn- pp-init []
  [[] (atom {:tbl nil})])

(defn- pp-getPgms [^nico.ui.ProgramsPanel this]
  (.getPgms ^nico.ui.ProgramsTableModel
            (.getModel ^nico.ui.ProgramsTable (:tbl @(.state this)))))

(defn- pp-setPgms [^nico.ui.ProgramsPanel this pgms]
  (do-swing
   (.setPgms ^nico.ui.ProgramsTableModel
             (.getModel ^nico.ui.ProgramsTable (:tbl @(.state this)))
             pgms)))

(defn- pp-post-init [^nico.ui.ProgramsPanel this]
  (let [tbl (doto (upt/pgm-table) (.setSortable true))
        spane (doto (JScrollPane. tbl) (-> .getViewport (.setBackground Color/WHITE)))
        layout (SpringLayout.)]
    (swap! (.state this) assoc :tbl tbl)
    (doto layout
      (.putConstraint SpringLayout/WEST spane 5 SpringLayout/WEST this)
      (.putConstraint SpringLayout/EAST spane -5 SpringLayout/EAST this)
      (.putConstraint SpringLayout/NORTH spane 5 SpringLayout/NORTH this)
      (.putConstraint SpringLayout/SOUTH spane -5 SpringLayout/SOUTH this))
    (doto this
      (.setLayout layout)
      (.add spane))))

(defn- pp-repaintTable [^nico.ui.ProgramsPanel this]
  (doto ^javax.swing.JTable (:tbl @(.state this))
    (.revalidate)
    (.repaint)))
