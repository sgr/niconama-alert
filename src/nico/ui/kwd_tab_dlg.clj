;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "keyword tab preference dialog."}
  nico.ui.kwd-tab-dlg
  (:use [clojure.contrib.swing-utils :only [do-swing add-action-listener]])
  (:require [nico.ui.btn :as ub])
  (:import (java.awt BorderLayout FlowLayout Dimension)
	   (javax.swing BorderFactory GroupLayout SpringLayout
			JButton JCheckBox JDialog JLabel JPanel JScrollPane JTextArea JTextField)
	   (javax.swing.event DocumentListener)
	   (javax.swing.text PlainDocument)))

(def *dlg-size* (Dimension. 450 320))
(def *btn-panel-size* (Dimension. 450 40))

(defn transq
  "translate query string into function sexp.
   It returns nil when malformed query string.
   If you call translated query function, eval it.
  \"word\" -> (fn [s] (re-find #\"(?i)word\" s))
  (and \"a\" (not \"b\")) -> (fn [s] (and (re-find #\"(?i)a\") (not (re-find #\"(?i)b\")))) "
  [q]
  (letfn [(tq [q]
	      (cond (list? q) (if (< 1 (count q))
				(let [op (first q)]
				  (if (or (= 'and op) (= 'or op) (= 'not op))
				    (cons op (map tq (rest q)))
				    (throw (IllegalArgumentException. "Malformed list was given."))))
				(throw (IllegalArgumentException. "Almost empty list was given.")))
		    (string? q) (if-not (empty? q)
				  (list 're-find (list 're-pattern (str "(?i)" q)) 's)
				  (throw (IllegalArgumentException. "Empty string was given.")))
		    :else (list 're-find (list 're-pattern (str "(?i)" q)) 's)))]
    (list 'fn '[s] (tq q))))

(defn keyword-tab-dialog
  [parent title pref ok-fn]
  (let [dlg (JDialog. parent title true)
	title-panel (JPanel.), title-doc (PlainDocument.)
	query-panel (JPanel.), query-doc (PlainDocument.)
	target-panel (JPanel.)
	cb-title (JCheckBox. "タイトル"), cb-desc (JCheckBox. "説明"), cb-owner (JCheckBox. "放送主")
	cb-category (JCheckBox. "カテゴリ"), cb-comm-name (JCheckBox. "コミュ名")
	btn-panel (JPanel.), btn-ok (ub/btn "OK")
	p (.getLocationOnScreen parent)]
    (letfn [(read-title [] (read-string (.getText title-doc 0 (.getLength title-doc))))
	    (read-query [] (read-string (.getText query-doc 0 (.getLength query-doc))))
	    (check []
		   (let [selected (or (.isSelected cb-title)
				      (.isSelected cb-desc)
				      (.isSelected cb-owner)
				      (.isSelected cb-category)
				      (.isSelected cb-comm-name))]
		     (if (or (= 0 (.getLength title-doc))
			     (= 0 (.getLength query-doc))
			     (false? selected))
		       (.setEnabled btn-ok false)
		       (try
			 (do (eval (transq (read-query))) (.setEnabled btn-ok true))
			 (catch Exception e (println (.getMessage e)) (.setEnabled btn-ok false))))))]
      (doto title-doc
	(.addDocumentListener (proxy [DocumentListener] []
				(changedUpdate [_] (check))
				(insertUpdate [_] (check))
				(removeUpdate [_] (check)))))
      (doto query-doc
	(.addDocumentListener (proxy [DocumentListener] []
				(changedUpdate [_] (check))
				(insertUpdate [_] (check))
				(removeUpdate [_] (check)))))
      (doseq [c [cb-title cb-desc cb-owner cb-category cb-comm-name]]
	(doto c (add-action-listener (fn [_] (check)))))
      (when-let [ts (:target pref)]
	(when (seq? ts) (doseq [t ts] (condp = t
					  :title (.setSelected cb-title true)
					  :desc (.setSelected cb-desc true)
					  :owner_name (.setSelected cb-owner true)
					  :category (.setSelected cb-category true)
					  :comm_name (.setSelected cb-comm-name true)
					  nil))))
      (let [title-label (JLabel. "タブタイトル"), title-field (JTextField. 25)
	    layout (GroupLayout. title-panel)
	    hgrp (.createSequentialGroup layout)
	    vgrp (.createSequentialGroup layout)]
	(doto title-field (.setDocument title-doc))
	(when-let [t (:title pref)] (.setText title-field t))
	(doto hgrp
	  (.addGroup (.. layout createParallelGroup (addComponent title-label)))
	  (.addGroup (.. layout createParallelGroup (addComponent title-field))))
	(doto vgrp
	  (.addGroup (.. layout createParallelGroup
			 (addComponent title-label) (addComponent title-field))))
	(doto layout
	  (.setHorizontalGroup hgrp) (.setVerticalGroup vgrp)
	  (.setAutoCreateGaps true) (.setAutoCreateContainerGaps true))
	(doto title-panel (.setLayout layout)))
      (let [query-area (JTextArea. 5 38)]
	(doto query-area
	  (.setLineWrap true)
	  (.setDocument query-doc))
	(when-let [q (:query pref)] (.setText query-area (pr-str q)))
	(doto query-panel
	  (.setBorder (BorderFactory/createTitledBorder "検索条件"))
	  (.add (JScrollPane. query-area))))
      (doto target-panel
	(.setBorder (BorderFactory/createTitledBorder "検索対象"))
	(.setLayout (FlowLayout.))
	(.add cb-title) (.add cb-desc) (.add cb-owner) (.add cb-category) (.add cb-comm-name))
      (doto btn-ok
	(add-action-listener
	 (fn [e] (do-swing
		  (.setVisible dlg false)
		  (ok-fn {:type :kwd
			  :title (str (read-title))
			  :query (read-query)
			  :target (filter #(not (nil? %))
					  (list (when (.isSelected cb-title) :title)
					        (when (.isSelected cb-desc) :desc)
					        (when (.isSelected cb-owner) :owner_name)
						(when (.isSelected cb-category) :category)
						(when (.isSelected cb-comm-name) :comm_name)))
			  :alert (:alert pref)})
		  (.dispose dlg)))))
      (.setDefaultButton (.getRootPane dlg) btn-ok)
      (let [btn-cancel (ub/btn "キャンセル"), layout (SpringLayout.)]
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
	  (.setLayout layout)
	  (.add btn-ok) (.add btn-cancel)
	  (.setPreferredSize *btn-panel-size*)))
      (let [cpane (.getContentPane dlg), layout (SpringLayout.)]
	(doto layout
	  (.putConstraint SpringLayout/WEST title-panel 5 SpringLayout/WEST cpane)
	  (.putConstraint SpringLayout/EAST title-panel -5 SpringLayout/EAST cpane)
	  (.putConstraint SpringLayout/WEST query-panel 5 SpringLayout/WEST cpane)
	  (.putConstraint SpringLayout/EAST query-panel -5 SpringLayout/EAST cpane)
	  (.putConstraint SpringLayout/WEST target-panel 5 SpringLayout/WEST cpane)
	  (.putConstraint SpringLayout/EAST target-panel -5 SpringLayout/EAST cpane)
	  (.putConstraint SpringLayout/WEST btn-panel 5 SpringLayout/WEST cpane)
	  (.putConstraint SpringLayout/EAST btn-panel -5 SpringLayout/EAST cpane)
	  (.putConstraint SpringLayout/NORTH title-panel 5 SpringLayout/NORTH cpane)
	  (.putConstraint SpringLayout/NORTH query-panel 5 SpringLayout/SOUTH title-panel)
	  (.putConstraint SpringLayout/NORTH target-panel 5 SpringLayout/SOUTH query-panel)
	  (.putConstraint SpringLayout/NORTH btn-panel 5 SpringLayout/SOUTH target-panel)
	  (.putConstraint SpringLayout/SOUTH btn-panel -5 SpringLayout/SOUTH cpane))
	(doto cpane
	  (.add title-panel) (.add query-panel) (.add target-panel) (.add btn-panel)
	  (.setLayout layout)))
      (check)
      (doto dlg
	(.setLocation (+ (.x p) (int (/ (- (.getWidth parent) (.getWidth *dlg-size*)) 2)))
		      (+ (.y p) (int (/ (- (.getHeight parent) (.getHeight *dlg-size*)) 2))))
	(.setResizable false)
	(.setMinimumSize *dlg-size*)))))

