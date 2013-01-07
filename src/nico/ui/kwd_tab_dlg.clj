;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "keyword tab preference dialog."}
  nico.ui.kwd-tab-dlg
  (:use [clojure.tools.swing-utils :only [do-swing add-action-listener]]
	[clojure.tools.logging])
  (:require [nico.ui.query-panel :as uq]
            [nico.ui.target-panel :as ut]
            [nico.ui.util :as uu])
  (:import [java.awt FlowLayout Dimension]
	   [javax.swing BorderFactory SpringLayout JDialog JLabel JPanel JTextField]
	   [javax.swing.event DocumentListener]
	   [javax.swing.text PlainDocument]))

(def ^{:private true} DLG-SIZE (Dimension. 450 340))
(def ^{:private true} TITLE-PANEL-SIZE (Dimension. 440 40))
(def ^{:private true} BTN-PANEL-SIZE (Dimension. 440 30))

(defn keyword-tab-dialog
  [parent title pref ok-fn]
  (let [dlg (JDialog. parent title true)
	title-panel (JPanel.), title-field (JTextField.), title-doc (PlainDocument.)
	title-border (.getBorder title-field)
        query-panel (nico.ui.QueryPanel. (:query pref))
        target-panel (nico.ui.TargetPanel. (:target pref))
	btn-panel (JPanel.), btn-ok (uu/btn "OK")
	p (.getLocationOnScreen parent)]
    (letfn [(read-title [] (.getText title-doc 0 (.getLength title-doc)))
            (check []
              (if (or (= 0 (.getLength title-doc))
                      (not (.isOK query-panel))
                      (not (.isOK target-panel)))
                (.setEnabled btn-ok false)
                (.setEnabled btn-ok true)))]
      (doto title-doc
	(.addDocumentListener (proxy [DocumentListener] []
				(changedUpdate [_] (check))
				(insertUpdate [_] (check))
				(removeUpdate [_] (check)))))
      (.addListener query-panel check)
      (.addListener target-panel check)
      (let [title-label (JLabel. "タブタイトル"), layout (SpringLayout.)]
	(doto title-field (.setDocument title-doc))
	(when-let [t (:title pref)] (.setText title-field t))
	(doto layout
	  (.putConstraint SpringLayout/NORTH title-label 5 SpringLayout/NORTH title-panel)
	  (.putConstraint SpringLayout/SOUTH title-label -5 SpringLayout/SOUTH title-panel)
	  (.putConstraint SpringLayout/NORTH title-field 10 SpringLayout/NORTH title-panel)
	  (.putConstraint SpringLayout/SOUTH title-field -10 SpringLayout/SOUTH title-panel)
	  (.putConstraint SpringLayout/WEST title-label 5 SpringLayout/WEST title-panel)
	  (.putConstraint SpringLayout/WEST title-field 10 SpringLayout/EAST title-label)
	  (.putConstraint SpringLayout/EAST title-field -10 SpringLayout/EAST title-panel))
	(doto title-panel
	  (.setPreferredSize TITLE-PANEL-SIZE)
	  (.setLayout layout) (.add title-label) (.add title-field)))
      (doto btn-ok
	(add-action-listener
	 (fn [e] (do-swing
		  (.setVisible dlg false)
		  (ok-fn {:type :kwd
			  :title (read-title)
			  :query (.getQuery query-panel)
			  :target (.getTargets target-panel)
			  :alert (:alert pref)})
		  (.dispose dlg)))))
      (.setDefaultButton (.getRootPane dlg) btn-ok)
      (let [btn-cancel (uu/btn "キャンセル"), layout (SpringLayout.)]
	(doto btn-cancel
	  (add-action-listener (fn [e] (do-swing (.setVisible dlg false) (.dispose dlg)))))
	(doto layout
	  (.putConstraint SpringLayout/SOUTH btn-ok -10 SpringLayout/SOUTH btn-panel)
	  (.putConstraint SpringLayout/SOUTH btn-cancel -10 SpringLayout/SOUTH btn-panel)
	  (.putConstraint SpringLayout/EAST btn-ok -5 SpringLayout/WEST btn-cancel)
	  (.putConstraint SpringLayout/EAST btn-cancel -10 SpringLayout/EAST btn-panel))
	(doto btn-panel
	  (.setLayout layout)
	  (.add btn-ok) (.add btn-cancel)
	  (.setPreferredSize BTN-PANEL-SIZE)))
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
        (.setDefaultCloseOperation JDialog/DISPOSE_ON_CLOSE)
	(.setLocation (+ (.x p) (int (/ (- (.getWidth parent) (.getWidth DLG-SIZE)) 2)))
		      (+ (.y p) (int (/ (- (.getHeight parent) (.getHeight DLG-SIZE)) 2))))
	(.setResizable false)
	(.setMinimumSize DLG-SIZE)))))
