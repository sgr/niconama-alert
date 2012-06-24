;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "keyword tab preference dialog."}
  nico.ui.search-panel
  (:use [clojure.tools.swing-utils :only [do-swing add-action-listener]]
	[clojure.tools.logging])
  (:require [nico.pgm :as pgm]
            [nico.ui.query-panel]
            [nico.ui.target-panel]
            [nico.ui.pgm-table :as upt]
            [nico.ui.util :as uu])
  (:import [java.awt BorderLayout Color FlowLayout Dimension]
	   [javax.swing GroupLayout GroupLayout$Alignment JDialog JLabel JPanel JScrollPane JTextField]
	   [javax.swing.event DocumentListener]
	   [javax.swing.text PlainDocument]))

(def ^{:private true} QUERY-PANEL-SIZE (Dimension. 450 110))
(def ^{:private true} TARGET-PANEL-SIZE (Dimension. 400 30))
(def ^{:private true} ADDTAB-DLG-SIZE (Dimension. 400 110))

(gen-class
 :name nico.ui.SearchPanel
 :extends javax.swing.JPanel
 :prefix "sp-"
 :constructors {[] []}
 :state state
 :init init
 :post-init post-init
 :methods [[setAddTabListener [clojure.lang.IFn] void]])

(defn- sp-init [] [[] (atom {:listener (fn [pref])})])

(defn- sp-setAddTabListener [this f]
  (swap! (.state this) assoc :listener f))

(defn- sp-post-init [this]
  (let [query-panel (nico.ui.QueryPanel. "")
        target-panel (nico.ui.TargetPanel. '())
        btn-search (uu/btn "検索")
        btn-tab (uu/btn "タブに追加")
        tbl (doto (upt/pgm-table) (.setSortable true))
        spane (doto (JScrollPane. tbl) (-> .getViewport (.setBackground Color/WHITE)))
        layout (GroupLayout. this)
        hgrp (.createParallelGroup layout)
        vgrp (.createSequentialGroup layout)
        check-all #(if (and (.isOK query-panel) (.isOK target-panel))
                     (do (.setEnabled btn-search true)
                         (.setEnabled btn-tab    true))
                     (do (.setEnabled btn-search false)
                         (.setEnabled btn-tab    false)))]
    (doto query-panel
      (.addListener      check-all)
      (.setMinimumSize   QUERY-PANEL-SIZE)
      (.setPreferredSize QUERY-PANEL-SIZE))
    (doto target-panel
      (.addListener      check-all)
      (.setMaximumSize   TARGET-PANEL-SIZE)
      (.setPreferredSize TARGET-PANEL-SIZE))
    (doto btn-search
      (add-action-listener
       (fn [e]
         (.setEnabled btn-search false)
         (let [pgms (pgm/search-pgms-by-keywords (.getQuery query-panel) (.getTargets target-panel))]
           (.setPgms (.getModel tbl) pgms))
         (.setEnabled btn-search true)))
      (.setDefaultCapable true)
      (.setEnabled false))
    (doto btn-tab
      (add-action-listener
       (fn [_]
         (let [parent (.getTopLevelAncestor this)
               dlg (JDialog. parent "キーワードタブの追加" true)
               tlabel (JLabel. "タブタイトル") tfield (JTextField.) tdoc (PlainDocument.)
               btn-ok (uu/btn "OK") btn-cancel (uu/btn "キャンセル")
               cpane (.getContentPane dlg) dlayout (GroupLayout. cpane)
               dhgrp (.createParallelGroup dlayout GroupLayout$Alignment/CENTER)
               dvgrp (.createSequentialGroup dlayout)
               p (.getLocationOnScreen parent)
               ok-fn (:listener @(.state this))]
           (letfn [(read-title [] (.getText tdoc 0 (.getLength tdoc)))
                   (check []
                     (if (< 0 (.getLength tdoc))
                       (.setEnabled btn-ok true)
                       (.setEnabled btn-ok false)))]
             (doto tdoc
               (.addDocumentListener
                (proxy [DocumentListener] []
                  (changedUpdate [_] (check))
                  (insertUpdate [_] (check))
                  (removeUpdate [_] (check)))))
             (doto btn-ok
               (add-action-listener
                (fn [_]
                  (do-swing
                    (.setVisible dlg false)
                    (ok-fn {:type :kwd
                            :title (read-title)
                            :query (.getQuery query-panel)
                            :target (.getTargets target-panel)
                            :alert true})
                    (.dispose dlg))))
               (.setEnabled false)))
           (.setDocument tfield tdoc)
           (doto btn-cancel
             (add-action-listener
              (fn [_] (do-swing (.setVisible dlg false) (.dispose dlg)))))
           (.setDefaultButton (.getRootPane dlg) btn-cancel)
           (doto dhgrp
             (.addGroup (.. dlayout createSequentialGroup
                            (addComponent tlabel)
                            (addComponent tfield)))
             (.addGroup (.. dlayout createSequentialGroup
                            (addComponent btn-ok)
                            (addComponent btn-cancel))))
           (doto dvgrp
             (.addGroup (.. dlayout (createParallelGroup GroupLayout$Alignment/CENTER)
                            (addComponent tlabel)
                            (addComponent tfield)))
             (.addGroup (.. dlayout createParallelGroup
                            (addComponent btn-ok)
                            (addComponent btn-cancel))))
           (doto dlayout
             (.setHorizontalGroup dhgrp) (.setVerticalGroup dvgrp)
             (.setAutoCreateGaps true) (.setAutoCreateContainerGaps true))
           (doto cpane
             (.setLayout dlayout)
             (.add tlabel) (.add tfield) (.add btn-ok) (.add btn-cancel))
           (doto dlg
             (.setLocation (+ (.x p) (int (/ (- (.getWidth parent) (.getWidth ADDTAB-DLG-SIZE)) 2)))
                           (+ (.y p) (int (/ (- (.getHeight parent) (.getHeight ADDTAB-DLG-SIZE)) 2))))
             (.setMinimumSize   ADDTAB-DLG-SIZE)
             (.setPreferredSize ADDTAB-DLG-SIZE)
             (.setResizable false)
             (.setVisible true)))))
      (.setDefaultCapable false)
      (.setEnabled false))
    (doto hgrp
      (.addGroup (.. layout createSequentialGroup
                     (addComponent query-panel)
                     (addGroup (.. layout (createParallelGroup GroupLayout$Alignment/CENTER)
                                    (addComponent target-panel)
                                    (addGroup (.. layout createSequentialGroup
                                                  (addComponent btn-search)
                                                  (addComponent btn-tab)))))))
      (.addComponent spane))
    (doto vgrp
      (.addGroup (.. layout createParallelGroup
                     (addComponent query-panel)
                     (addGroup (.. layout createSequentialGroup
                                    (addComponent target-panel)
                                    (addGroup (.. layout createParallelGroup
                                                  (addComponent btn-search)
                                                  (addComponent btn-tab)))))))
      (.addComponent spane))
    (doto layout
      (.setHorizontalGroup hgrp)
      (.setVerticalGroup vgrp)
      (.setAutoCreateGaps true)
      (.setAutoCreateContainerGaps true))
    (doto this
      (.setLayout layout)
      (.add query-panel)
      (.add target-panel)
      (.add btn-tab)
      (.add btn-search)
      (.add spane))))

