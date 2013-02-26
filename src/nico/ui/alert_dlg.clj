;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Alert dialog."}
  nico.ui.alert-dlg
  (:use [clojure.tools.swing-utils :only [do-swing-and-wait]]
        [clojure.tools.logging])
  (:require [nico.prefs :as p]
            [nico.ui.util :as uu]
            [net-utils :as n]
            [str-utils :as su]
            [time-utils :as tu])
  (:import [java.awt Color Component Container Cursor Dimension FlowLayout Font GraphicsEnvironment
            GridBagLayout GridBagConstraints Insets]
           [java.awt.event ActionListener ComponentAdapter MouseEvent MouseListener MouseMotionListener]
           [java.awt.geom RoundRectangle2D$Float]
           [java.net URL]
           [java.util.concurrent TimeUnit]
           [javax.swing BorderFactory ImageIcon JButton JDialog JLabel JPanel JTextArea SpringLayout]
           [javax.swing.text.html HTMLEditorKit]))

(def ^{:private true} OPACITY (float 0.9))
(defn- get-shape [^JDialog dlg] (RoundRectangle2D$Float. 0 0 (.getWidth dlg) (.getHeight dlg) 20 20))

(def ^{:private true} ^Dimension ASIZE (Dimension. 220 130))
(def ^{:private true} ^Cursor LINK-CURSOR (.getLinkCursor (HTMLEditorKit.)))
(def ^{:private true} ^ImageIcon CLOSE-ICON (ImageIcon. (clojure.java.io/resource "closebtn.png")))
(def ^{:private true} ^Dimension CLOSE-ICON-SIZE (Dimension. (.getIconWidth CLOSE-ICON) (.getIconHeight CLOSE-ICON)))
(def ^{:private true} ^Dimension TITLE-PANEL-SIZE (Dimension. 210 18))
(def ^{:private true} ^Color MONLY-BGCOLOR (Color. 165 204 255))
(def ^{:private true} ^Dimension DESC-SIZE (Dimension. 115 64))
(def ^{:private true} ^long RETRY-LIMIT 5)

(defn- decorate-aux-sun-java6 []
  (try
    (let [cAu (Class/forName "com.sun.awt.AWTUtilities")
          cWT (Class/forName "com.sun.awt.AWTUtilities$Translucency")
          mVO (.getMethod cWT "valueOf" (into-array Class [String]))
          TRANSLUCENT (.invoke mVO nil (to-array ["TRANSLUCENT"]))
          PERPIXEL_TRANSLUCENT (.invoke mVO nil (to-array ["PERPIXEL_TRANSLUCENT"]))
          PERPIXEL_TRANSPARENT (.invoke mVO nil (to-array ["PERPIXEL_TRANSPARENT"]))
          mIsTS (.getMethod cAu "isTranslucencySupported" (into-array Class [cWT]))
          supported-tl  (.booleanValue ^Boolean (.invoke mIsTS nil (to-array [TRANSLUCENT])))
          supported-ptl (.booleanValue ^Boolean (.invoke mIsTS nil (to-array [PERPIXEL_TRANSLUCENT])))
          supported-ptp (.booleanValue ^Boolean (.invoke mIsTS nil (to-array [PERPIXEL_TRANSPARENT])))]
      (info (format "This environment is supported TRANSLUCENT: %s" supported-tl))
      (info (format "This environment is supported PERPIXCEL_TRANSLUCENT: %s" supported-ptl))
      (info (format "This environment is supported PERPIXCEL_TRANSPARENT: %s" supported-ptp))
      (let [cWindow (Class/forName "java.awt.Window")
            cShape (Class/forName "java.awt.Shape")
            mSetWO (.getMethod cAu "setWindowOpacity" (into-array Class [cWindow Float/TYPE]))
            mSetWS (.getMethod cAu "setWindowShape" (into-array Class [cWindow cShape]))]
        (fn [^JDialog dlg]
          (try
            (when supported-tl (.invoke mSetWO nil (to-array [dlg OPACITY])))
            (when supported-ptp (.invoke mSetWS nil (to-array [dlg (get-shape dlg)])))
            (catch Exception e (warn (.getCause e) "failed invoking method"))))))
    (catch Exception e
      (warn e "This platform doesn't support AWTUtilities") nil)))

(defn- decorate-aux-java7 []
  (let [cWT (Class/forName "java.awt.GraphicsDevice$WindowTranslucency")
        mVO (.getMethod cWT "valueOf" (into-array Class [String]))
        TRANSLUCENT (.invoke mVO nil (to-array ["TRANSLUCENT"]))
        PERPIXEL_TRANSLUCENT (.invoke mVO nil (to-array ["PERPIXEL_TRANSLUCENT"]))
        PERPIXEL_TRANSPARENT (.invoke mVO nil (to-array ["PERPIXEL_TRANSPARENT"]))
        ge (GraphicsEnvironment/getLocalGraphicsEnvironment)
        gd (.getDefaultScreenDevice ge)
        supported-tl  (.isWindowTranslucencySupported gd TRANSLUCENT)
        supported-ptl (.isWindowTranslucencySupported gd PERPIXEL_TRANSLUCENT)
        supported-ptp (.isWindowTranslucencySupported gd PERPIXEL_TRANSPARENT)]
    (info (format "This environment is supported TRANSLUCENT: %s" supported-tl))
    (info (format "This environment is supported PERPIXCEL_TRANSLUCENT: %s" supported-ptl))
    (info (format "This environment is supported PERPIXCEL_TRANSPARENT: %s" supported-ptp))
    (fn [^JDialog dlg]
      (.setUndecorated dlg true)
      (when supported-tl  (.setOpacity dlg OPACITY))
      (when supported-ptp (.setShape   dlg (get-shape dlg))))))

(let [decorate-fn (atom nil)]
  (defn- decorate [dlg]
    (when-not @decorate-fn
      (let [v (System/getProperty "java.version")]
        (debug (str "java.version: " v))
        (condp #(.startsWith ^String %2 ^String %1) v
          "1.6" (when-let [f (decorate-aux-sun-java6)]
                  (debug "use Sun Java SE6 Update 10 API (AWTUtilities)")
                  (reset! decorate-fn f))
          "1.7" (when-let [f (decorate-aux-java7)]
                  (debug "use Java 7 API")
                  (reset! decorate-fn f)))
        (when-not @decorate-fn (reset! decorate-fn (fn [dlg])))))
    (@decorate-fn dlg)))

(defn dlg-width  [] (.width  ASIZE))
(defn dlg-height [] (.height ASIZE))

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

(defn alert-dlg [^nico.pgm.Pgm pgm ^ImageIcon thumbicn extra-close-fn]
  (let [dlg (JDialog.)]
    (let [tpanel (JPanel.), dpanel (JPanel.)
	  owner (su/ifstr (:owner_name pgm) "")
	  comm_name (su/ifstr (:comm_name pgm) (:comm_id pgm))
	  olabel (JLabel. (format " %s (%s)" owner comm_name))
	  time (JLabel. (format "%s  （%d分前に開始）"
				(if (:member_only pgm) "※コミュ限" "")
				(tu/minute (tu/interval (:pubdate pgm) (tu/now)))))
          close-fn (fn [e]
                     (do-swing-and-wait (.setVisible dlg false) (.dispose dlg))
                     (extra-close-fn))]
      (let [title (JLabel. ^String (su/ifstr (:title pgm) (name (:id pgm))))
	    cbtn (JButton. ^ImageIcon CLOSE-ICON), layout (SpringLayout.)
            close-listener (proxy [ActionListener][]
                             (actionPerformed [e]
                               (do-swing-and-wait
                                (.setVisible dlg false)
                                (.removeActionListener cbtn this)
                                (.dispose dlg))
                               (extra-close-fn)))]
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
      (let [cpane (.getContentPane dlg), layout (SpringLayout.), s 5, ns -5, is 2]
        (doto layout
          (.putConstraint SpringLayout/WEST tpanel s SpringLayout/WEST cpane)
          (.putConstraint SpringLayout/EAST tpanel ns SpringLayout/EAST cpane)
          (.putConstraint SpringLayout/WEST dpanel s SpringLayout/WEST cpane)
          (.putConstraint SpringLayout/EAST dpanel ns SpringLayout/EAST cpane)
          (.putConstraint SpringLayout/WEST olabel s SpringLayout/WEST cpane)
          (.putConstraint SpringLayout/EAST olabel ns SpringLayout/EAST cpane)
          (.putConstraint SpringLayout/WEST time s SpringLayout/WEST cpane)
          (.putConstraint SpringLayout/EAST time ns SpringLayout/EAST cpane)
          (.putConstraint SpringLayout/NORTH tpanel s SpringLayout/NORTH cpane)
          (.putConstraint SpringLayout/NORTH dpanel s SpringLayout/SOUTH tpanel)
          (.putConstraint SpringLayout/NORTH olabel is SpringLayout/SOUTH dpanel)
          (.putConstraint SpringLayout/NORTH time is SpringLayout/SOUTH olabel)
          (.putConstraint SpringLayout/SOUTH time ns SpringLayout/SOUTH cpane))
        (when (:member_only pgm)
          (.setBackground cpane MONLY-BGCOLOR)
          (.setBackground tpanel MONLY-BGCOLOR)
          (.setBackground dpanel MONLY-BGCOLOR))
        (doto cpane
          (.setLayout layout)
          (.add tpanel) (.add dpanel) (.add olabel) (.add time))))
    (doto dlg
      (.setDefaultCloseOperation JDialog/DISPOSE_ON_CLOSE)
      (.addComponentListener
       (proxy [ComponentAdapter][] (componentResized [e] (decorate dlg))))
      (.setFocusableWindowState false)
      (.setAlwaysOnTop true)
      (.setMinimumSize ASIZE)
      (.setUndecorated true))))
