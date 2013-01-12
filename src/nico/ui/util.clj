;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "UI Utilities."}
  nico.ui.util
  (:require [time-utils :as tu])
  (:import [java.awt Color Component Container Dimension Font]
	   [javax.swing JButton JLabel JTable JTextArea SpringLayout]
	   [javax.swing.table DefaultTableCellRenderer]))

(def ^Font DEFAULT-FONT (Font. "Default" Font/PLAIN 12))
(def ^{:private true} BTN-HEIGHT 25)
(def ^{:private true} BTN-SIZE (Dimension. 100 BTN-HEIGHT))
(def ^{:private true} ODD-ROW-COLOR (Color. 224 233 246))

(defn ^JButton btn [text]
  (doto (JButton.)
    (.setText text)
    (.setMaximumSize BTN-SIZE)
    (.setMinimumSize BTN-SIZE)
    (.setPreferredSize BTN-SIZE)))

(defn do-add-expand
  "親コンポーネントに子を追加する。その際、指定されたパディングを残して一杯にひろげる。"
  [^Container parent ^Component child pad]
  (let [layout (SpringLayout.)]
    (doto layout
      (.putConstraint SpringLayout/NORTH child ^int pad     SpringLayout/NORTH parent)
      (.putConstraint SpringLayout/SOUTH child ^int (- pad) SpringLayout/SOUTH parent)
      (.putConstraint SpringLayout/WEST  child ^int pad     SpringLayout/WEST  parent)
      (.putConstraint SpringLayout/EAST  child ^int (- pad) SpringLayout/EAST  parent))
    (doto parent
      (.setLayout layout)
      (.add child))))

(defn ^JTextArea mlabel
  "複数行折り返し可能なラベル"
  ([^String text]
     (doto (JTextArea. text)
       (.setFont DEFAULT-FONT)
       (.setOpaque false) (.setEditable false) (.setFocusable false) (.setLineWrap true)))
  ([^String text ^Dimension size]
     (doto ^Component (mlabel text) (.setPreferredSize size))))

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
  [^nico.ui.StripeRenderer this ^JTable tbl val selected focus row col]
  (.superGtcrc this tbl val selected focus row col)
  (if selected
    (doto this
      (.setForeground (.getSelectionForeground tbl))
      (.setBackground (.getSelectionBackground tbl)))
    (doto this
      (.setForeground (.getForeground tbl))
      (.setBackground (if (odd? row) ODD-ROW-COLOR (.getBackground tbl)))))
  this)
(defn- sr-setValue [^nico.ui.StripeRenderer this ^Object val]
  (.setText
   this
   (if val
     (condp = (class val)
       java.util.Date (do (.setHorizontalTextPosition this DefaultTableCellRenderer/CENTER)
                          (tu/format-time-short val))
       (.toString val))
     "")))
