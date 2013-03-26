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

(defn- render-stripe [^javax.swing.table.TableCellRenderer rdr ^JTable tbl selected row]
  (if selected
    (doto rdr
      (.setForeground (.getSelectionForeground tbl))
      (.setBackground (.getSelectionBackground tbl)))
    (doto rdr
      (.setForeground (.getForeground tbl))
      (.setBackground (if (odd? row) ODD-ROW-COLOR (.getBackground tbl))))))

(gen-class
 :name nico.ui.MultiLineRenderer
 :extends javax.swing.JTextArea
 :implements [javax.swing.table.TableCellRenderer]
 :methods [[updateFontInfo [] void]]
 :exposes-methods {setFont superSetFont
                   getTableCellRendererComponent superGtcrc}
 :prefix "mr-"
 :state state
 :init init
 :post-init post-init)

(defn- mr-init [] [[] (atom nil)])

(defn- mr-updateFontInfo [^nico.ui.MultiLineRenderer this]
  (let [fm (.getFontMetrics this (.getFont this))]
    (reset! (.state this) {:fm fm
                           :fh (+ (.getHeight fm) 2)})))

(defn- mr-post-init [^nico.ui.MultiLineRenderer this]
  (.setEditable this false)
  (.setLineWrap this true)
  (.setWrapStyleWord this false)
  (.updateFontInfo this))

 ;; (defn- mr-setFont [^nico.ui.MultiLineRenderer this ^Font font]
 ;;  (.superSetFont this font)
 ;;  (.updateFontInfo this))

(defn text-component-height [^nico.ui.MultiLineRenderer c]
  (let [text-len (.stringWidth (:fm @(.state c)) (.getText c))
        width (.getWidth c)
        lines (inc (quot text-len width))]
    (* (:fh @(.state c)) lines)))

(defn- update-table-row-height
  ([^javax.swing.table.TableCellRenderer rdr ^JTable tbl row height]
     (let [rh (.getRowHeight tbl row)]
       (when (> height rh)
         (debug (format "updated height: %d -> %d" rh height))
         (do-swing (.setRowHeight tbl row height)))))
  ([^javax.swing.table.TableCellRenderer rdr ^JTable tbl row]
     (let [nh (if (instance? nico.ui.MultiLineRenderer rdr)
                (text-component-height rdr)
                (.height (.getPreferredSize rdr)))]
       (update-table-row-height rdr tbl row nh))))

(defn- mr-getTableCellRendererComponent [^nico.ui.MultiLineRenderer this ^JTable tbl ^Object val selected focus row col]
  (letfn [(fix-size [^nico.ui.MultiLineRenderer mlr ^JTable tbl row]
            (let [width (.width (.getPreferredSize mlr))
                  height (text-component-height mlr)]
              (.setPreferredSize mlr (Dimension. width height))
              (update-table-row-height mlr tbl row height)))]
    (.superGtcrc this tbl val selected focus row col)
    (render-stripe this tbl selected row)
    (doto this
      (.setText
       (if val
         (condp instance? val
           java.util.Date (tu/format-time-short val)
           (.toString val))
         ""))
      (.setSize (Dimension. (-> tbl .getTableHeader .getColumnModel (.getColumn col) .getWidth) 1000))
      (fix-size tbl row))))

(defn- mr-invalidate [^nico.ui.MultiLineRenderer this])
(defn- mr-validate [^nico.ui.MultiLineRenderer this])
(defn- mr-revalidate [^nico.ui.MultiLineRenderer this])
;; (defn- mr-repaint
;;   ([^nico.ui.MultiLineRenderer this tm x y width height])
;;   ([^nico.ui.MultiLineRenderer this x y width height])
;;   ([^nico.ui.MultiLineRenderer this r])
;;   ([^nico.ui.MultiLineRenderer this]))

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
  (render-stripe this tbl selected row)
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
