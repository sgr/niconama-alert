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
           [setPgms [clojure.lang.IPersistentMap] void]])

(defn- pp-init []
  [[] (atom {:tbl nil})])

(defn- pp-setPgms [this pgms]
  (do-swing (.setPgms (.getModel (:tbl @(.state this))) pgms)))

(defn- pp-post-init [this]
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

(defn- pp-repaintTable [this]
  (doto (:tbl @(.state this))
    (.revalidate)
    (.repaint)))
