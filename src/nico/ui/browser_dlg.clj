;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "browser setting dialog."}
  nico.ui.browser-dlg
  (:use [clojure.contrib.swing-utils :only [do-swing add-action-listener]])
  (:require [nico.ui.btn :as ub]
	    [nico.ui.key-val-dlg :as ukvd])
  (:import (java.awt BorderLayout Dimension)
	   (javax.swing GroupLayout SpringLayout
			JButton JDialog JLabel JPanel JScrollPane JTable
			ListSelectionModel DefaultListSelectionModel)
	   (javax.swing.event ListSelectionListener)
	   (javax.swing.table DefaultTableModel TableColumn)))

(def *dlg-size* (Dimension. 450 250))
(def *btn-panel-size* (Dimension. 450 40))

(defn- make-model [pref]
  (let [model (proxy [DefaultTableModel] []
		(isCellEditable [r c] false))]
    (doto model (.addColumn "ブラウザ名") (.addColumn "パス"))
    (doseq [[name cmd] pref] (let [a (make-array Object 2)] (aset a 0 name) (aset a 1 cmd) (.addRow model a)))
    model))

(defn browsers-dialog
  [parent title pref ok-fn]
  (let [dlg (JDialog. parent title true), cpane (.getContentPane dlg)
	browsers (atom pref), p (.getLocationOnScreen parent)
	tbl (JTable. (make-model @browsers)), tbtn-panel (JPanel.)
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
	  (.addListSelectionListener (proxy [ListSelectionListener] []
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
			(swap! browsers conj (vector name cmd))
			(do-swing (.setModel tbl (make-model @browsers)))))]
	     (do-swing (.setVisible bcd true))))))
      (doto tbtn-edit
	(add-action-listener
	 (fn [e]
	   (let [r (.getSelectedRow tbl)
		 [old-name old-cmd] (nth @browsers r)
		 bcd (ukvd/browser-command-dialog
		      dlg "ブラウザの編集" old-name old-cmd
		      (fn [name cmd]
			(swap! browsers assoc r (vector name cmd))
			(do-swing (.setModel tbl (make-model @browsers)))))]
	     (do-swing (.setVisible bcd true))))))
      (doto tbtn-rem
	(add-action-listener
	 (fn [e]
	   (let [r (.getSelectedRow tbl)]
	     (reset! browsers (vec (concat (subvec @browsers 0 r) (subvec @browsers (inc r)))))
	     (do-swing (.setModel tbl (make-model @browsers)))))))
      (doto tbtn-up
	(add-action-listener
	 (fn [e]
	   (let [r (.getSelectedRow tbl)]
	     (swap! browsers (fn [brs idx]
			       (let [uidx (dec idx), uitm (nth brs uidx), bitm (nth brs idx)]
				 (assoc (assoc brs uidx bitm) idx uitm))) r)
	     (do-swing (.setModel tbl (make-model @browsers)))))))
      (doto tbtn-down
	(add-action-listener
	 (fn [e]
	   (let [r (.getSelectedRow tbl)]
	     (swap! browsers (fn [brs idx]
			       (let [bidx (inc idx), uitm (nth brs idx), bitm (nth brs bidx)]
				 (assoc (assoc brs idx bitm) bidx uitm))) r)
	     (do-swing (.setModel tbl (make-model @browsers)))))))
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
	 (fn [e] (do-swing (.setVisible dlg false) (ok-fn @browsers) (.dispose dlg)))))
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
