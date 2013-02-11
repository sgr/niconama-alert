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
 :name nico.ui.MultiLineRenderer
 :extends javax.swing.JTextArea
 :implements [javax.swing.table.TableCellRenderer]
;; :exposes-methods {isOpaque superIsOpaque}
 :prefix "mr-"
 :post-init post-init)

(defn- mr-post-init [^nico.ui.MultiLineRenderer this]
  (.setEditable this false)
  (.setLineWrap this true)
  (.setWrapStyleWord this true))

(defn- mr-getTableCellRendererComponent [^nico.ui.MultiLineRenderer this ^JTable tbl ^Object val selected focus row col]
  (if val
    (condp = (class val)
      java.util.Date (.setText this (tu/format-time-short val))
      (.setText this (.toString val)))
    (.setText this ""))
  (.setRowHeight tbl 32)
  this)

(defn- mr-invalidate [^nico.ui.MultiLineRenderer this])
(defn- mr-validate [^nico.ui.MultiLineRenderer this])
(defn- mr-revalidate [^nico.ui.MultiLineRenderer this])
(defn- mr-firePropertyChange [^nico.ui.MultiLineRenderer this propertyName oldValue newValue])
(defn- mr-repaint
  ([^nico.ui.MultiLineRenderer this tm x y width height])
  ([^nico.ui.MultiLineRenderer this x y width height])
  ([^nico.ui.MultiLineRenderer this r])
  ([^nico.ui.MultiLineRenderer this]))
;; (defn- mr-isOpaque [^nico.ui.MultiLineRenderer this]
;;   (let [back (.getBackground this)
;;         p (when-let [p1 (.getParent this)] (.getParent p1))]
;;     (and (not (and back p (.getBackground p) (.isOpaque p)))
;;          (.superIsOpaque this))))

(defn- set-row-color [this ^JTable tbl selected row]
  (if selected
    (doto this
      (.setForeground (.getSelectionForeground tbl))
      (.setBackground (.getSelectionBackground tbl)))
    (doto this
      (.setForeground (.getForeground tbl))
      (.setBackground (if (odd? row) ODD-ROW-COLOR (.getBackground tbl))))))

(gen-class
 :name nico.ui.StripeRenderer
 :extends nico.ui.MultiLineRenderer
 :exposes-methods {getTableCellRendererComponent superGtcrc}
 :prefix "sr-")

(defn- sr-init [] [[] nil])
(defn- sr-getTableCellRendererComponent [^nico.ui.StripeRenderer this ^JTable tbl val selected focus row col]
  (.superGtcrc this tbl val selected focus row col)
;;  (set-row-color this tbl selected row)
  (if selected
    (doto this
      (.setForeground (.getSelectionForeground tbl))
      (.setBackground (.getSelectionBackground tbl)))
    (doto this
      (.setForeground (.getForeground tbl))
      (.setBackground (if (odd? row) ODD-ROW-COLOR (.getBackground tbl)))))
  this)

(gen-class
 :name nico.ui.StripeImageCellRenderer
 :extends javax.swing.table.DefaultTableCellRenderer
 :exposes-methods {getTableCellRendererComponent superGtcrc}
 :prefix "sicr-")

(defn- sicr-init [] [[] nil])
(defn- sicr-getTableCellRendererComponent [^nico.ui.StripeImageCellRenderer this ^JTable tbl val selected focus row col]
  (.superGtcrc this tbl val selected focus row col)
;;  (set-row-color this tbl selected row)
  (if selected
    (doto this
      (.setForeground (.getSelectionForeground tbl))
      (.setBackground (.getSelectionBackground tbl)))
    (doto this
      (.setForeground (.getForeground tbl))
      (.setBackground (if (odd? row) ODD-ROW-COLOR (.getBackground tbl)))))
  this)

(defn- sicr-setValue [^nico.ui.StripeImageCellRenderer this ^Object val]
  (if val
    (condp = (class val)
      javax.swing.ImageIcon (.setIcon this val)
      java.util.Date (do (.setHorizontalTextPosition this DefaultTableCellRenderer/CENTER)
                         (.setText this (tu/format-time-short val)))
      (.setText this (.toString val)))
    (.setText this "")))
