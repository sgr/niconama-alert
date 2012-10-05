;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "タブに閉じるボタンと種別アイコンがついたJTabbedPane"}
  nico.ui.tab-component
  (:use [clojure.tools.swing-utils :only [do-swing add-action-listener]])
  (:import [java.awt BorderLayout Dimension]
	   [javax.swing BorderFactory ImageIcon JButton JLabel JPanel]))

(def ^{:private true} COMM-TAB-ICON  (ImageIcon. (clojure.java.io/resource "usrtab.png")))
(def ^{:private true} KWD-TAB-ICON   (ImageIcon. (clojure.java.io/resource "kwdtab.png")))
(def ^{:private true} CLOSE-BTN-ICON (ImageIcon. (clojure.java.io/resource "closebtn.png")))

(gen-class
 :name nico.ui.TabComponent
 :extends javax.swing.JPanel
 :prefix "tc-"
 :constructors {[clojure.lang.Keyword] []}
 :state state
 :init init
 :post-init post-init
 :methods [[setTitle [String] void]
           [getTabMenuItems [] clojure.lang.ISeq]
           [addCloseButtonListener [clojure.lang.IFn] void]])

(defn- tc-init [type]
  [[] (atom {:title-label nil :close-button nil})])

(defn- tc-setTitle [this title]
  (when-let [title-label (:title-label @(.state this))]
    (do-swing (.setText title-label title))))

(defn- tc-addCloseButtonListener [this f]
  (when-let [close-button (:close-button @(.state this))]
    (add-action-listener close-button f)))

(defn- tc-post-init [this type]
  (.setLayout this (BorderLayout.))
  (if (= type :all)
    (let [ltitle (JLabel.)]
      (doto ltitle (.setBorder (BorderFactory/createEmptyBorder 0 4 0 4)))
      (swap! (.state this) assoc :title-label ltitle)
      (doto this
	(.setOpaque false)
	(.add ltitle BorderLayout/CENTER)
	(.setBorder (BorderFactory/createEmptyBorder 2 0 0 0))))
    (let [ticn (condp = type :comm COMM-TAB-ICON :kwd KWD-TAB-ICON)
	  cicn CLOSE-BTN-ICON
	  lticn (JLabel. ticn)
	  ltitle (JLabel.)
	  cbtn (JButton. cicn)]
      (doto ltitle (.setBorder (BorderFactory/createEmptyBorder 0 4 0 10)))
      (swap! (.state this) assoc :title-label ltitle)
      (doto lticn
	(.setPreferredSize (Dimension. (.getIconWidth ticn) (.getIconHeight ticn))))
      (doto cbtn
	(.setPreferredSize (Dimension. (.getIconWidth cicn) (.getIconHeight cicn))))
      (swap! (.state this) assoc :close-button cbtn)
      (doto this
	(.setOpaque false)
	(.add lticn BorderLayout/WEST)
	(.add ltitle BorderLayout/CENTER)
	(.add cbtn BorderLayout/EAST)
	(.setBorder (BorderFactory/createEmptyBorder 3 0 2 0))))))

