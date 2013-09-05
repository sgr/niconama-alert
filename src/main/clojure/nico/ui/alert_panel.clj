;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr" :doc "Alert panel."}
  nico.ui.alert-panel
  (:require [clojure.tools.logging :as log]
            [nico.prefs :as p]
            [nico.ui.util :as uu]
            [net-utils :as n]
            [str-utils :as su]
            [time-utils :as tu])
  (:import [java.awt Color Component Container Cursor Dimension FlowLayout Font GraphicsEnvironment
            GridBagLayout GridBagConstraints Insets]
           [java.awt.event ActionListener ComponentAdapter MouseEvent MouseListener MouseMotionListener WindowEvent]
           [java.awt.geom RoundRectangle2D$Float]
           [java.net URL]
           [java.util.concurrent TimeUnit]
           [javax.swing BorderFactory ImageIcon JButton JDialog JLabel JPanel JTextArea SpringLayout]
           [javax.swing.text.html HTMLEditorKit]))

(def OPACITY (float 0.9))
(def ^{:private true} ^Dimension ASIZE (Dimension. 220 130))

(defn dlg-width  [] (.width  ASIZE))
(defn dlg-height [] (.height ASIZE))
(defn get-shape  [] (RoundRectangle2D$Float. 0 0 (.width ASIZE) (.height ASIZE) 20 20))

(def ^{:private true} ^Cursor LINK-CURSOR (.getLinkCursor (HTMLEditorKit.)))
(def ^{:private true} ^ImageIcon CLOSE-ICON (ImageIcon. (clojure.java.io/resource "closebtn.png")))
(def ^{:private true} ^Dimension CLOSE-ICON-SIZE (Dimension. (.getIconWidth CLOSE-ICON) (.getIconHeight CLOSE-ICON)))
(def ^{:private true} ^Dimension TITLE-PANEL-SIZE (Dimension. 210 18))
(def ^{:private true} ^Color MONLY-BGCOLOR (Color. 165 204 255))
(def ^{:private true} ^Dimension DESC-SIZE (Dimension. 115 64))
(def ^{:private true} ^long RETRY-LIMIT 5)

(defn- change-cursor [^Component c url]
  (let [csr (.getCursor c)]
    (doto c
      (.addMouseListener
       (proxy [MouseListener][]
	 (mouseEntered  [^MouseEvent e] (.setCursor ^Component (.getSource e) LINK-CURSOR))
	 (mouseExited   [^MouseEvent e] (.setCursor ^Component (.getSource e) csr))
	 (mousePressed  [^MouseEvent e] (p/open-url :alert url))
	 (mouseClicked  [^MouseEvent e])
         (mouseReleased [^MouseEvent e]))))))

(defn alert-panel [^nico.pgm.Pgm pgm ^ImageIcon thumbicn]
  (let [apanel (JPanel.)
        tpanel (JPanel.)
        dpanel (JPanel.)
        owner (su/ifstr (:owner_name pgm) "")
        comm_name (su/ifstr (:comm_name pgm) (:comm_id pgm))
        olabel (JLabel. (format " %s (%s)" owner comm_name))
        time (JLabel. (format "%s  （%d分前に開始）"
                              (if (:member_only pgm) "※コミュ限" "")
                              (tu/minute (tu/interval (:pubdate pgm) (tu/now)))))]
    (let [title (JLabel. ^String (su/ifstr (:title pgm) (name (:id pgm))))
          cbtn (JButton. ^ImageIcon CLOSE-ICON), layout (SpringLayout.)
          close-listener (proxy [ActionListener][]
                             (actionPerformed [e]
                               (let [p (.getParent apanel)]
                                 (when (and p (instance? java.awt.Window p))
                                   (log/debug (format "p is Window: %s" (pr-str p)))
                                   (.dispatchEvent p (WindowEvent. p WindowEvent/WINDOW_CLOSING))
                                   (.removeActionListener cbtn this)))))]
      (doto title (.setFont uu/DEFAULT-FONT))
      (doto layout
        (.putConstraint SpringLayout/NORTH title 0 SpringLayout/NORTH tpanel)
        (.putConstraint SpringLayout/SOUTH title 0 SpringLayout/SOUTH tpanel)
        (.putConstraint SpringLayout/NORTH cbtn 0 SpringLayout/NORTH tpanel)
        (.putConstraint SpringLayout/SOUTH cbtn 0 SpringLayout/SOUTH tpanel)
        (.putConstraint SpringLayout/WEST title 0 SpringLayout/WEST tpanel)
        (.putConstraint SpringLayout/EAST title 0 SpringLayout/WEST cbtn)
        (.putConstraint SpringLayout/EAST cbtn 0 SpringLayout/EAST tpanel))
      (doto cbtn
        (.addActionListener close-listener)
        (.setPreferredSize CLOSE-ICON-SIZE))
      (when (:member_only pgm) (.setBackground cbtn MONLY-BGCOLOR))
      (doto tpanel
        (.setPreferredSize TITLE-PANEL-SIZE)
        (.setLayout layout) (.add title) (.add cbtn)))
    (let [thumbnail (JLabel. thumbicn),
          ^Component desc (uu/mlabel (su/ifstr (:desc pgm) "") DESC-SIZE)
          layout (GridBagLayout.), c (GridBagConstraints.)]
      (letfn [(set-con!
                [^GridBagLayout lt component x y top left bottom right]
                (set! (.gridx c) x) (set! (.gridy c) y) (set! (.fill c) GridBagConstraints/BOTH)
                (set! (.insets c) (Insets. top left bottom right)) (.setConstraints lt component c))]
        (set-con! layout thumbnail 0 0 0 0 0 0)
        (set-con! layout desc 1 0 0 5 0 0))
      (change-cursor thumbnail (:link pgm)) (change-cursor desc (:link pgm))
      (doto ^Container dpanel
            (.setLayout layout) (.add thumbnail) (.add desc)))
    (doto time (.setHorizontalAlignment (JLabel/RIGHT)) (.setFont uu/DEFAULT-FONT))
    (doto olabel (.setFont uu/DEFAULT-FONT))
    (let [layout (SpringLayout.) s 5 ns -5 is 2]
      (doto layout
        (.putConstraint SpringLayout/WEST tpanel s SpringLayout/WEST apanel)
        (.putConstraint SpringLayout/EAST tpanel ns SpringLayout/EAST apanel)
        (.putConstraint SpringLayout/WEST dpanel s SpringLayout/WEST apanel)
        (.putConstraint SpringLayout/EAST dpanel ns SpringLayout/EAST apanel)
        (.putConstraint SpringLayout/WEST olabel s SpringLayout/WEST apanel)
        (.putConstraint SpringLayout/EAST olabel ns SpringLayout/EAST apanel)
        (.putConstraint SpringLayout/WEST time s SpringLayout/WEST apanel)
        (.putConstraint SpringLayout/EAST time ns SpringLayout/EAST apanel)
        (.putConstraint SpringLayout/NORTH tpanel s SpringLayout/NORTH apanel)
        (.putConstraint SpringLayout/NORTH dpanel s SpringLayout/SOUTH tpanel)
        (.putConstraint SpringLayout/NORTH olabel is SpringLayout/SOUTH dpanel)
        (.putConstraint SpringLayout/NORTH time is SpringLayout/SOUTH olabel)
        (.putConstraint SpringLayout/SOUTH time ns SpringLayout/SOUTH apanel))
      (when (:member_only pgm)
        (.setBackground apanel MONLY-BGCOLOR)
        (.setBackground tpanel MONLY-BGCOLOR)
        (.setBackground dpanel MONLY-BGCOLOR))
      (doto apanel
        (.setLayout layout)
        (.add tpanel) (.add dpanel) (.add olabel) (.add time)))))
