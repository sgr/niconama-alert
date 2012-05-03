;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "common key-value dialog."}
  nico.ui.key-val-dlg
  (:use [clojure.tools.swing-utils :only [do-swing add-action-listener]])
  (:require [nico.ui.util :as uu])
  (:import [java.awt BorderLayout Color Dimension]
	   [javax.swing BorderFactory GroupLayout SpringLayout
			JButton JDialog JLabel JPanel JPasswordField JTextField]
	   [javax.swing.event DocumentListener]
	   [javax.swing.text PlainDocument]))

(def ^{:private true} DLG-SIZE (Dimension. 450 150))
(def ^{:private true} BTN-PANEL-SIZE (Dimension. 450 40))

(defn- kv-dlg
  [parent title label-key key label-val val ok-fn secure]
  (let [dlg (JDialog. parent title true), cpane (.getContentPane dlg)
	tkey (JTextField. 25), tval (if secure (JPasswordField. 25) (JTextField. 25))
	tkey-border (.getBorder tkey), tval-border (.getBorder tval)
	doc-key (PlainDocument.), doc-val (PlainDocument.)
	kv-panel (JPanel.), btn-panel (JPanel.), btn-ok (uu/btn "OK")
	p (.getLocationOnScreen parent)]
    (letfn [(check []
		   (if (= 0 (.getLength doc-key))
		     (.setBorder tkey (BorderFactory/createLineBorder Color/RED))
		     (.setBorder tkey tkey-border))
		   (if (= 0 (.getLength doc-val))
		     (.setBorder tval (BorderFactory/createLineBorder Color/RED))
		     (.setBorder tval tkey-border))
		   (if (or (= 0 (.getLength doc-key)) (= 0 (.getLength doc-val)))
			(.setEnabled btn-ok false) (.setEnabled btn-ok true)))]
      (doto doc-key
	(.addDocumentListener (proxy [DocumentListener] []
				(changedUpdate [_] (check))
				(insertUpdate [_] (check))
				(removeUpdate [_] (check)))))
      (doto doc-val
	(.addDocumentListener (proxy [DocumentListener] []
				(changedUpdate [_] (check))
				(insertUpdate [_] (check))
				(removeUpdate [_] (check)))))
      (let [lkey (JLabel. label-key), lval (JLabel. label-val)
	    layout (GroupLayout. kv-panel)
	    hgrp (.createSequentialGroup layout), vgrp (.createSequentialGroup layout)]
	(doto tkey (.setDocument doc-key))
	(doto tval (.setDocument doc-val))
	(when key (.setText tkey key))
	(when val (.setText tval val))
	(doto hgrp
	  (.addGroup (.. layout createParallelGroup (addComponent lkey) (addComponent lval)))
	  (.addGroup (.. layout createParallelGroup (addComponent tkey) (addComponent tval))))
	(doto vgrp
	  (.addGroup (.. layout createParallelGroup (addComponent lkey) (addComponent tkey)))
	  (.addGroup (.. layout createParallelGroup (addComponent lval) (addComponent tval))))
	(doto layout
	  (.setHorizontalGroup hgrp) (.setVerticalGroup vgrp)
	  (.setAutoCreateGaps true) (.setAutoCreateContainerGaps true))
	(doto kv-panel
	  (.setLayout layout) (.add lkey) (.add tkey) (.add lval) (.add tval)))
      (let [btn-cancel (uu/btn "キャンセル")
	    btn-layout (SpringLayout.)]
	(doto btn-cancel
	  (add-action-listener (fn [e] (do-swing (.setVisible dlg false) (.dispose dlg)))))
	(doto btn-layout
	  (.putConstraint SpringLayout/NORTH btn-ok 5 SpringLayout/NORTH btn-panel)
	  (.putConstraint SpringLayout/SOUTH btn-ok -10 SpringLayout/SOUTH btn-panel)
	  (.putConstraint SpringLayout/NORTH btn-cancel 5 SpringLayout/NORTH btn-panel)
	  (.putConstraint SpringLayout/SOUTH btn-cancel -10 SpringLayout/SOUTH btn-panel)
	  (.putConstraint SpringLayout/EAST btn-ok -5 SpringLayout/WEST btn-cancel)
	  (.putConstraint SpringLayout/EAST btn-cancel -10 SpringLayout/EAST btn-panel))
	(doto btn-panel
	  (.setPreferredSize BTN-PANEL-SIZE)
	  (.setLayout btn-layout) (.add btn-ok) (.add btn-cancel)))
      (doto btn-ok
	(add-action-listener
	 (fn [e] (do-swing
		  (.setVisible dlg false)
		  (ok-fn
		   (.getText doc-key 0 (.getLength doc-key))
		   (.getText doc-val 0 (.getLength doc-val)))
		  (.dispose dlg)))))
      (.setDefaultButton (.getRootPane dlg) btn-ok)
      (check)
      (doto cpane
	(.setLayout (BorderLayout.))
	(.add kv-panel BorderLayout/CENTER)
	(.add btn-panel BorderLayout/SOUTH))
      (doto dlg
	(.setLocation (+ (.x p) (int (/ (- (.getWidth parent) (.getWidth DLG-SIZE)) 2)))
		      (+ (.y p) (int (/ (- (.getHeight parent) (.getHeight DLG-SIZE)) 2))))
	(.setResizable false)
	(.setMinimumSize DLG-SIZE)))))

(defn user-password-dialog
  [parent title pref ok-fn]
  (kv-dlg parent title "Email:" (:email pref) "Password:" (:passwd pref)
	  (fn [email passwd] (ok-fn (assoc pref :email email :passwd passwd))) true))

(defn browser-command-dialog
  [parent title key val ok-fn]
  (kv-dlg parent title "Browser name:" key "Browser command:" val ok-fn false))
