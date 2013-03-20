;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "UI Utilities."}
  nico.ui.util
  (:use [clojure.tools.logging]
        [clojure.tools.swing-utils :only [do-swing]])
  (:require [time-utils :as tu])
  (:import [java.awt Color Component Container Dimension Font]
	   [javax.swing JButton JLabel JTable JTextArea SpringLayout SwingConstants]
           [javax.swing.table DefaultTableCellRenderer]))

(def ^Font DEFAULT-FONT (Font. "Default" Font/PLAIN 12))
(def THUMBNAIL-WIDTH 32)
(def THUMBNAIL-HEIGHT 32)

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

(defn text-component-height [^javax.swing.text.JTextComponent c]
  (let [fm (.getFontMetrics c (.getFont c))
        fh (+ (.getHeight fm) 2)
        text-len (.stringWidth fm (.getText c))
        width (.getWidth c)
        lines (int (inc (/ text-len width)))]
    (* fh lines)))

(defn- update-table-row-height
  ([^javax.swing.table.TableCellRenderer rdr ^JTable tbl row height]
     (let [rh (.getRowHeight tbl row)]
       (when (> height rh)
         (debug (format "updated height: %d -> %d" rh height))
         (do-swing (.setRowHeight tbl row height)))))
  ([^javax.swing.table.TableCellRenderer rdr ^JTable tbl row]
     (let [nh (if (instance? javax.swing.text.JTextComponent rdr)
                (text-component-height rdr)
                (.height (.getPreferredSize rdr)))]
       (update-table-row-height rdr tbl row nh))))

(defn- mr-getTableCellRendererComponent [^nico.ui.MultiLineRenderer this ^JTable tbl ^Object val selected focus row col]
  (doto this
    (.setText
     (if val
       (condp instance? val
         java.util.Date (tu/format-time-short val)
         (.toString val))
       ""))
    (.setSize (Dimension. (-> tbl .getTableHeader .getColumnModel (.getColumn col) .getWidth) 1000))
    #(let [width (.width (.getPreferredSize %))
           height (text-component-height %)]
       (.setPreferredSize % (Dimension. width height))
       (update-table-row-height tbl row height))))

(defn- mr-invalidate [^nico.ui.MultiLineRenderer this])
(defn- mr-validate [^nico.ui.MultiLineRenderer this])
(defn- mr-revalidate [^nico.ui.MultiLineRenderer this])
;; (defn- mr-repaint
;;   ([^nico.ui.MultiLineRenderer this tm x y width height])
;;   ([^nico.ui.MultiLineRenderer this x y width height])
;;   ([^nico.ui.MultiLineRenderer this r])
;;   ([^nico.ui.MultiLineRenderer this]))

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
  (set-row-color this tbl selected row)
  this)

(gen-class
 :name nico.ui.StripeImageCellRenderer
 :extends javax.swing.table.DefaultTableCellRenderer
 :exposes-methods {getTableCellRendererComponent superGtcrc}
 :prefix "sicr-"
 :constructors {[] []}
 :post-init post-init)

(defn- sicr-post-init [^nico.ui.StripeImageCellRenderer this]
  (.setHorizontalTextPosition this SwingConstants/CENTER)
  (.setHorizontalAlignment this SwingConstants/CENTER))

(defn- sicr-getTableCellRendererComponent [^nico.ui.StripeImageCellRenderer this ^JTable tbl val selected focus row col]
  (.superGtcrc this tbl val selected focus row col)
  (set-row-color this tbl selected row)
  (when (and val (instance? javax.swing.ImageIcon val))
    (let [size (Dimension. (+ 8 THUMBNAIL-WIDTH) (+ 8 THUMBNAIL-HEIGHT))]
      (doto this
        (.setSize size)
        (.setPreferredSize size)
        (.setMinimumSize size))))
  (update-table-row-height this tbl row)
  this)

(defn- sicr-setValue [^nico.ui.StripeImageCellRenderer this ^Object val]
  (if val
    (condp instance? val
      javax.swing.ImageIcon (.setIcon this val)
      java.util.Date (.setText this (tu/format-time-short val))
      (.setText this (.toString val)))
    (.setText this "")))
