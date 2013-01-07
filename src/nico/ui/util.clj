;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "UI Utilities."}
  nico.ui.util
  (:require [time-utils :as tu])
  (:import [java.awt Color Dimension Font]
	   [javax.swing JButton JLabel JTextArea SpringLayout]
	   [javax.swing.table DefaultTableCellRenderer]))

(def DEFAULT-FONT (Font. "Default" Font/PLAIN 12))
(def ^{:private true} BTN-HEIGHT 25)
(def ^{:private true} BTN-SIZE (Dimension. 100 BTN-HEIGHT))
(def ^{:private true} ODD-ROW-COLOR (Color. 224 233 246))

(defn btn [text]
  (let [b (JButton.)]
    (doto b
      (.setText text)
      (.setMaximumSize BTN-SIZE)
      (.setMinimumSize BTN-SIZE)
      (.setPreferredSize BTN-SIZE))))

(defn do-add-expand
  "親コンポーネントに子を追加する。その際、指定されたパディングを残して一杯にひろげる。"
  [parent child pad]
  (let [layout (SpringLayout.)]
    (doto layout
      (.putConstraint SpringLayout/NORTH child pad SpringLayout/NORTH parent)
      (.putConstraint SpringLayout/SOUTH child (* -1 pad) SpringLayout/SOUTH parent)
      (.putConstraint SpringLayout/WEST child pad SpringLayout/WEST parent)
      (.putConstraint SpringLayout/EAST child (* -1 pad) SpringLayout/EAST parent))
    (doto parent
      (.setLayout layout)
      (.add child))))

(defn mlabel
  "複数行折り返し可能なラベル"
  ([^String text]
     (let [l (JTextArea. text)]
       (doto l
	 (.setFont DEFAULT-FONT)
	 (.setOpaque false) (.setEditable false) (.setFocusable false) (.setLineWrap true))))
  ([^String text ^Dimension size]
     (let [ml (mlabel text)]
       (doto ml
	 (.setPreferredSize size)))))

(gen-class
 :name nico.ui.StripeRenderer
 :extends javax.swing.table.DefaultTableCellRenderer
 :exposes-methods {getTableCellRendererComponent superGtcrc}
 :prefix "sr-"
 :constructors {[] []}
 :state state
 :init init)

(defn- sr-init [] [[] nil])
(defn- sr-getTableCellRendererComponent
  [this tbl val selected focus row col]
  (.superGtcrc this tbl val selected focus row col)
  (if selected
    (doto this
      (.setForeground (.getSelectionForeground tbl))
      (.setBackground (.getSelectionBackground tbl)))
    (doto this
      (.setForeground (.getForeground tbl))
      (.setBackground (if (odd? row) ODD-ROW-COLOR (.getBackground tbl)))))
  this)
(defn- sr-setValue [this val]
  (.setText
   this
   (if val
     (condp = (class val)
       java.util.Date (do (.setHorizontalTextPosition this DefaultTableCellRenderer/CENTER)
                          (tu/format-time-short val))
       (.toString val))
     "")))
