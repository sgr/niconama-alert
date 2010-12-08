;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "browser setting dialog."}
  nico.ui.browser-dlg
  (:use [clojure.contrib.swing-utils :only [do-swing add-action-listener]])
  (:require [nico.ui.btn :as ub]
	    [nico.ui.key-val-dlg :as ukvd])
  (:import (java.awt BorderLayout Dimension)
	   (javax.swing DefaultCellEditor GroupLayout SpringLayout SwingConstants
			JButton JCheckBox JDialog JLabel JPanel JScrollPane JTable
			ListSelectionModel DefaultListSelectionModel)
	   (javax.swing.event CellEditorListener ChangeEvent TableModelEvent
			      ListSelectionListener TableModelListener)
	   (javax.swing.table DefaultTableCellRenderer DefaultTableModel TableColumn
			      TableCellEditor TableCellRenderer)))

(def *dlg-size* (Dimension. 500 250))
(def *btn-panel-size* (Dimension. 500 40))

(defn- browsers-to-model [browsers]
  (let [model
	(proxy [DefaultTableModel][]
	  (isCellEditable [r c] (if (= 2 c) (if (.getValueAt this r c) false true) false)))]
    (doto model (.addColumn "ブラウザ名") (.addColumn "パス") (.addColumn "アラート"))
    (doseq [[name cmd alert] browsers]
      (.addRow model (doto (make-array Object 3) (aset 0 name) (aset 1 cmd) (aset 2 alert))))
    model))

(defn- model-to-browsers [model]
  (vec (for [r (range (.getRowCount model))]
	 [(.getValueAt model r 0) (.getValueAt model r 1) (.getValueAt model r 2)])))

(defn- string-renderer []
  (proxy [DefaultTableCellRenderer] []
    (getTableCellRendererComponent
     [tbl val isSelected hasFocus row col]
     (let [c (proxy-super getTableCellRendererComponent tbl val isSelected hasFocus row col)]
       (condp = col
	   0 (when (= :default val) (.setText c "デフォルトブラウザ"))
	   1 (when (= :default val) (.setText c "-")))
       c))))

(defn- boolean-renderer []
  (proxy [TableCellRenderer] []
    (getTableCellRendererComponent
     [tbl val isSelected hasFocus row col]
     (let [c (JCheckBox.), m (.getModel tbl)
	   mrow (.convertRowIndexToModel tbl row), mcol (.convertColumnIndexToModel tbl col)
	   b (.getValueAt m mrow mcol)]
       (if isSelected
	 (doto c (.setForeground (.getSelectionForeground tbl)) (.setBackground (.getSelectionBackground tbl)))
	 (doto c (.setForeground (.getForeground tbl)) (.setBackground (.getBackground tbl))))
       (doto c
	 (.setOpaque true)
	 (.setHorizontalAlignment SwingConstants/CENTER)
	 (.setVerticalAlignment SwingConstants/CENTER)
       	 (.setSelected b))))))

(defn- set-cols! [tbl cr]
  (let [cm (.getColumnModel tbl), cb (JCheckBox.), bce (DefaultCellEditor. cb)]
    (doto bce
      (.addCellEditorListener
       (proxy [CellEditorListener][]
	 (editingStopped [e] (.setValueAt (.getModel tbl) false @cr 2))
	 (editingCanceled [e]))))
    (doto cb (.setOpaque true)
	  (.setHorizontalAlignment SwingConstants/CENTER)
	  (.setVerticalAlignment SwingConstants/CENTER))
    (doto (.getColumn cm 0) (.setPreferredWidth 220) (.setCellRenderer (string-renderer)))
    (doto (.getColumn cm 1) (.setPreferredWidth 220) (.setCellRenderer (string-renderer)))
    (doto (.getColumn cm 2) (.setPreferredWidth 60)
	  (.setCellEditor bce) (.setCellRenderer (boolean-renderer)))))

(defn- browser-tbl [model]
  (letfn [(checked-row [m] (some #(if (.getValueAt m % 2) % false) (range (.getRowCount m))))]
    (let [cr (atom -1)
	  tbl (proxy [JTable][]
		(editCellAt [r c e] (let [result (proxy-super editCellAt r c e)]
				      (when result (reset! cr (checked-row (.getModel this))))
				      result))
		(setModel [m] (proxy-super setModel m) (set-cols! this cr)))]
      (doto tbl (.setModel model)))))

(defn browsers-dialog
  [parent title browsers ok-fn]
  (let [dlg (JDialog. parent title true), cpane (.getContentPane dlg)
	p (.getLocationOnScreen parent)
	tbl (browser-tbl (browsers-to-model browsers)), tbtn-panel (JPanel.)
	btn-panel (JPanel.), btn-ok (ub/btn "OK")]
    (let [tbl-sel-model (DefaultListSelectionModel.)
	  tbtn-add (JButton. "追加"), tbtn-edit (JButton. "編集"), tbtn-rem (JButton. "削除")
	  tbtn-up (JButton. "上へ"), tbtn-down (JButton. "下へ")
	  layout (GroupLayout. tbtn-panel)
	  hgrp (.createSequentialGroup layout), vgrp (.createSequentialGroup layout)]
      (letfn [(tbtns-enabled
	       [add edit rem up down]
	       (do-swing
		(.setEnabled tbtn-add add) (.setEnabled tbtn-edit edit) (.setEnabled tbtn-rem rem)
		(.setEnabled tbtn-up up) (.setEnabled tbtn-down down)))]
	(tbtns-enabled true false false false false)
	(doto tbl-sel-model
	  (.setSelectionMode ListSelectionModel/SINGLE_SELECTION)
	  (.addListSelectionListener
	   (proxy [ListSelectionListener] []
	     (valueChanged
	      [e]
	      (let [i (.getSelectedRow tbl), m (.getRowCount (.getModel tbl))]
		(cond (= 0 m) (tbtns-enabled true false false false false)
		      (= 1 m) (if (= -1 i)
				(tbtns-enabled true false false false false)
				(tbtns-enabled true true true false false))
		      :else (cond (= -1 i) (tbtns-enabled true false false false false)
				  (= 0 i) (tbtns-enabled true true true false true)
				  (= (dec m) i) (tbtns-enabled true true true true false)
				  :else (tbtns-enabled true true true true true)))))))))
      (doto tbl (.setSelectionModel tbl-sel-model))
      (doto tbtn-add
	(add-action-listener
	 (fn [e]
	   (let [bcd (ukvd/browser-command-dialog
		      dlg "ブラウザの追加" nil nil
		      (fn [name cmd]
			(.addRow (.getModel tbl)
				 (doto (make-array Object 3) (aset 0 name) (aset 1 cmd) (aset 2 false)))))]
	     (do-swing (.setVisible bcd true))))))
      (doto tbtn-edit
	(add-action-listener
	 (fn [e]
	   (let [r (.getSelectedRow tbl)
		 old-name (.getValueAt (.getModel tbl) r 0), old-cmd (.getValueAt (.getModel tbl) r 1)
		 bcd (ukvd/browser-command-dialog
		      dlg "ブラウザの編集" old-name old-cmd
		      (fn [name cmd]
			(doto (.getModel tbl) (.setValueAt name r 0) (.setValueAt cmd r 1))))]
	     (do-swing (.setVisible bcd true))))))
      (doto tbtn-rem
	(add-action-listener
	 (fn [e] (.removeRow (.getModel tbl) (.getSelectedRow tbl)))))
      (doto tbtn-up
	(add-action-listener
	 (fn [e] (let [r (.getSelectedRow tbl), to-r (dec r)]
		   (when-not (= 0 r)
		     (.moveRow (.getModel tbl) r r to-r)
		     (.setSelectionInterval tbl-sel-model to-r to-r))))))
      (doto tbtn-down
	(add-action-listener
	 (fn [e] (let [r (.getSelectedRow tbl), to-r (inc r)
		       m (.getModel tbl), maxr (dec (.getRowCount m))]
		   (when-not (= maxr r)
		     (.moveRow m r r to-r)
		     (.setSelectionInterval tbl-sel-model to-r to-r))))))
      (doto hgrp
	(.addGroup (.. layout createParallelGroup (addComponent tbtn-add) (addComponent tbtn-edit)
		       (addComponent tbtn-rem) (addComponent tbtn-up) (addComponent tbtn-down))))
      (doto vgrp
	(.addGroup (.. layout createParallelGroup (addComponent tbtn-add)))
	(.addGroup (.. layout createParallelGroup (addComponent tbtn-edit)))
	(.addGroup (.. layout createParallelGroup (addComponent tbtn-rem)))
	(.addGroup (.. layout createParallelGroup (addComponent tbtn-up)))
	(.addGroup (.. layout createParallelGroup (addComponent tbtn-down))))
      (doto layout
	(.setHorizontalGroup hgrp) (.setVerticalGroup vgrp)
	(.setAutoCreateGaps true) (.setAutoCreateContainerGaps true))
      (doto tbtn-panel
	(.setLayout layout) (.add tbtn-add) (.add tbtn-edit) (.add tbtn-rem)))
    (.setDefaultButton (.getRootPane dlg) btn-ok)
    (let [layout (SpringLayout.), btn-cancel (ub/btn "キャンセル")]
      (doto btn-ok
	(add-action-listener
	 (fn [e]
	   (do-swing (.setVisible dlg false) (ok-fn (model-to-browsers (.getModel tbl))) (.dispose dlg)))))
      (doto btn-cancel
	(add-action-listener (fn [e] (do-swing (.setVisible dlg false) (.dispose dlg)))))
      (doto layout
	(.putConstraint SpringLayout/NORTH btn-ok 5 SpringLayout/NORTH btn-panel)
	(.putConstraint SpringLayout/SOUTH btn-ok -10 SpringLayout/SOUTH btn-panel)
	(.putConstraint SpringLayout/NORTH btn-cancel 5 SpringLayout/NORTH btn-panel)
	(.putConstraint SpringLayout/SOUTH btn-cancel -10 SpringLayout/SOUTH btn-panel)
	(.putConstraint SpringLayout/EAST btn-ok -5 SpringLayout/WEST btn-cancel)
	(.putConstraint SpringLayout/EAST btn-cancel -10 SpringLayout/EAST btn-panel))
      (doto btn-panel
	(.setPreferredSize *btn-panel-size*)
	(.setLayout layout) (.add btn-ok) (.add btn-cancel)))
    (doto cpane
      (.setLayout (BorderLayout.)) (.add (JScrollPane. tbl) BorderLayout/CENTER)
      (.add tbtn-panel BorderLayout/EAST) (.add btn-panel BorderLayout/SOUTH))
    (doto dlg
      (.setLocation (+ (.x p) (int (/ (- (.getWidth parent) (.getWidth *dlg-size*)) 2)))
		    (+ (.y p) (int (/ (- (.getHeight parent) (.getHeight *dlg-size*)) 2))))
      (.setResizable false)
      (.setMinimumSize *dlg-size*))))
