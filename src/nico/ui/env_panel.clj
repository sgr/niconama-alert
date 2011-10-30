;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "environment info panel."}
  nico.ui.env-panel
  (:use [clojure.contrib.swing-utils :only [do-swing add-action-listener]]
	[clojure.contrib.seq-utils :only [indexed]])
  (:require [nico.ui.util :as uu])
  (:import (java.awt BorderLayout Color Dimension Font GridBagLayout GridBagConstraints Insets)
	   (javax.swing BorderFactory BoxLayout SpringLayout
			JButton JDialog JLabel JPanel JScrollPane JTable)
	   (javax.swing.table AbstractTableModel DefaultTableModel
			      DefaultTableCellRenderer
			      DefaultTableColumnModel TableColumn)))

(def *dlg-size* (Dimension. 450 250))
(def *cr-panel-size* (Dimension. 450 80))
(def *btn-panel-size* (Dimension. 450 40))
(def *odd-row-color* (Color. 224 233 246))

(defn- tbl-renderer []
  (proxy [DefaultTableCellRenderer][]
    (getTableCellRendererComponent
     [tbl val selected focus row col]
     (proxy-super getTableCellRendererComponent tbl val selected focus row col)
     (if selected
       (doto this
	 (.setForeground (.getSelectionForeground tbl))
	 (.setBackground (.getSelectionBackground tbl)))
       (doto this
	 (.setForeground (.getForeground tbl))
	 (.setBackground (if (odd? row) *odd-row-color* (.getBackground tbl)))))
     this)))

(def *coldef*
     (list
      {:colName "key", :width 100, :class String
       :renderer (tbl-renderer)}
      {:colName "value", :width 300, :class String
       :renderer (tbl-renderer)}))

(gen-class
 :name nico.ui.EnvTableModel
 :extends javax.swing.table.AbstractTableModel
 :prefix "etm-"
 :constructors {[] []}
 :state state
 :init init)

(defn- etm-init []
  [[] (atom (sort-by #(let [[k v] %] k) #(.compareTo %1 %2) (System/getProperties)))])
(defn- etm-getColumnClass [this col] (:class (nth *coldef* col)))
(defn- etm-getColumnCount [this] (count *coldef*))
(defn- etm-getColumnName [this col] (:colName (nth *coldef* col)))
(defn- etm-getRowCount [this] (count @(.state this)))
(defn- etm-getValueAt [this row col] (let [[k v] (nth @(.state this) row)] (if (= 0 col) k v)))
(defn- etm-isCellEditable [this row col] true)

(defn- env-column-model []
  (letfn [(gen-col [i pc]
		   (doto (TableColumn. i (:width pc))
		     (.setHeaderValue (:colName pc))
		     (.setCellRenderer (:renderer pc))))]
    (let [col-model (DefaultTableColumnModel.)]
      (doseq [[i pc] (indexed *coldef*)] (.addColumn col-model (gen-col i pc)))
      col-model)))

(defn env-panel []
  (doto (JPanel.)
    (.setLayout (BorderLayout.))
    (.add (JScrollPane. (doto (JTable. (nico.ui.EnvTableModel.) (env-column-model))
			  (.setAutoCreateRowSorter true)))
	  BorderLayout/CENTER)))

