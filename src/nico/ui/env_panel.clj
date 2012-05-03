;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "environment info panel."}
  nico.ui.env-panel
  (:import [java.awt BorderLayout Dimension]
	   [javax.swing JPanel JScrollPane JTable]
	   [javax.swing.table AbstractTableModel
			      DefaultTableColumnModel TableColumn]))

(def ^{:private true} COLDEF
     (list
      {:colName "key", :width 100, :class String
       :renderer (nico.ui.StripeRenderer.)}
      {:colName "value", :width 300, :class String
       :renderer (nico.ui.StripeRenderer.)}))

(gen-class
 :name nico.ui.EnvTableModel
 :extends javax.swing.table.AbstractTableModel
 :prefix "etm-"
 :constructors {[] []}
 :state state
 :init init)

(defn- etm-init []
  [[] (atom (sort-by #(let [[k v] %] k) #(.compareTo %1 %2) (System/getProperties)))])
(defn- etm-getColumnClass [this col] (:class (nth COLDEF col)))
(defn- etm-getColumnCount [this] (count COLDEF))
(defn- etm-getColumnName [this col] (:colName (nth COLDEF col)))
(defn- etm-getRowCount [this] (count @(.state this)))
(defn- etm-getValueAt [this row col] (let [[k v] (nth @(.state this) row)] (if (= 0 col) k v)))
(defn- etm-isCellEditable [this row col] true)

(defn- env-column-model []
  (letfn [(gen-col [i pc]
		   (doto (TableColumn. i (:width pc))
		     (.setHeaderValue (:colName pc))
		     (.setCellRenderer (:renderer pc))))]
    (let [col-model (DefaultTableColumnModel.)]
      (doseq [[i pc] (map-indexed vector COLDEF)] (.addColumn col-model (gen-col i pc)))
      col-model)))

(defn env-panel []
  (doto (JPanel.)
    (.setLayout (BorderLayout.))
    (.add (JScrollPane. (doto (JTable. (nico.ui.EnvTableModel.) (env-column-model))
			  (.setAutoCreateRowSorter true)))
	  BorderLayout/CENTER)))

