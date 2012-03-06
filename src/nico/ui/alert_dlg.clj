;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Alert dialog."}
  nico.ui.alert-dlg
  (:use [clojure.contrib.swing-utils :only [add-action-listener do-swing*]]
	[clojure.contrib.logging])
  (:require [nico.prefs :as p]
	    [nico.ui.util :as uu]
	    [net-utils :as n]
	    [str-utils :as su]
	    [time-utils :as tu])
  (:import [java.awt Color Dimension FlowLayout Font GraphicsEnvironment RenderingHints
		     GridBagLayout GridBagConstraints Insets]
	   [java.awt.event ComponentAdapter MouseListener MouseMotionListener]
	   [java.awt.geom RoundRectangle2D$Float]
	   [java.awt.image BufferedImage]
	   [java.net URL]
           [java.util.concurrent TimeUnit]
	   [javax.swing BorderFactory ImageIcon JButton JDialog JLabel JPanel JTextArea SpringLayout]
	   [javax.swing.text.html HTMLEditorKit]
	   [javax.imageio ImageIO]))

(def *opacity* (float 0.9))
(defn- get-shape [dlg] (RoundRectangle2D$Float. 0 0 (.getWidth dlg) (.getHeight dlg) 20 20))

(def *asize* (Dimension. 220 130))
(def *lcsr* (.getLinkCursor (HTMLEditorKit.)))
(def *cicn* (ImageIcon. (.getResource (.getClassLoader (class (fn []))) "closebtn.png")))
(def *noimg* (ImageIO/read (.getResource (.getClassLoader (class (fn []))) "noimage.png")))
(def *monly-bgcolor* (Color. 165 204 255))
(def *desc-size* (Dimension. 115 64))
(def *retry-limit* 5)

(defn- decorate-aux-sun-java6 []
  (try
    (let [cAu (Class/forName "com.sun.awt.AWTUtilities")
          cWindow (Class/forName "java.awt.Window")
          cShape (Class/forName "java.awt.Shape")
          setwo (.getMethod cAu "setWindowOpacity" (into-array Class [cWindow Float/TYPE]))
          setws (.getMethod cAu "setWindowShape" (into-array Class [cWindow cShape]))]
      (fn [dlg]
        (.invoke setwo nil (to-array [dlg *opacity*]))
        (doto dlg
          (.addComponentListener
           (proxy [ComponentAdapter][]
             (componentResized
               [e]
               (try
                 (.invoke setws nil (to-array [dlg (get-shape dlg)]))
                 (catch Exception e
                   (warn "failed invoking setWindowShape" e)))))))))
    (catch Exception e
      (warn "This platform doesn't support AWTUtilities" e) nil)))

(defn- decorate-aux-java7 []
  (let [cWT (Class/forName "java.awt.GraphicsDevice$WindowTranslucency")
        vo (.getMethod cWT "valueOf" (into-array Class [String]))
        TRANSLUCENT (.invoke vo nil (to-array ["TRANSLUCENT"]))
        PERPIXEL_TRANSLUCENT (.invoke vo nil (to-array ["PERPIXEL_TRANSLUCENT"]))
        PERPIXEL_TRANSPARENT (.invoke vo nil (to-array ["PERPIXEL_TRANSPARENT"]))
        ge (GraphicsEnvironment/getLocalGraphicsEnvironment)
        gd (.getDefaultScreenDevice ge)
        supported-tl  (.isWindowTranslucencySupported gd TRANSLUCENT)
        supported-ptl (.isWindowTranslucencySupported gd PERPIXEL_TRANSLUCENT)
        supported-ptp (.isWindowTranslucencySupported gd PERPIXEL_TRANSPARENT)]
    (debug (format "This environment is supported TRANSLUCENT: %s" supported-tl))
    (debug (format "This environment is supported PERPIXCEL_TRANSLUCENT: %s" supported-ptl))
    (debug (format "This environment is supported PERPIXCEL_TRANSPARENT: %s" supported-ptp))
    (fn [dlg]
      (try
        (.setUndecorated dlg true)
        (when supported-tl  (.setOpacity dlg  *opacity*))
        (when supported-ptl (.setShape dlg (get-shape dlg)))
        (catch Exception e (warn "Exception raised" e))))))

(let [decorate-fn (atom nil)]
  (defn- decorate [dlg]
    (when-not @decorate-fn
      (let [v (System/getProperty "java.version")]
        (debug (str "java.version: " v))
        (condp #(.startsWith %2 %1) v
          "1.6" (when-let [f (decorate-aux-sun-java6)] (reset! decorate-fn f))
          "1.7" (when-let [f (decorate-aux-java7)] (reset! decorate-fn f)))
        (when-not @decorate-fn (reset! decorate-fn (fn [dlg])))))
    (@decorate-fn dlg)))

(defn dlg-width [] (.width *asize*))
(defn dlg-height [] (.height *asize*))

(defn- change-cursor [c url]
  (let [csr (.getCursor c)]
    (doto c
      (.addMouseListener
       (proxy [MouseListener][]
	 (mouseEntered [e] (.setCursor (.getSource e) *lcsr*))
	 (mouseExited [e] (.setCursor (.getSource e) csr))
	 (mousePressed [e] (p/open-url :alert url))
	 (mouseClicked [e]) (mouseReleased [e]))))))

(defn- adjust-img [img width height]
  (let [nimg (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
	g2d (.createGraphics nimg)]
    (doto g2d
      (.setRenderingHint RenderingHints/KEY_INTERPOLATION
			 RenderingHints/VALUE_INTERPOLATION_BILINEAR)
      (.drawImage img 0 0 width height nil))
    nimg))

(defn- fetch-image [url]
  (try
    (ImageIO/read (n/url-stream url))
    (catch Exception _ nil)))

(defn- get-thumbnail-aux [url]
  (loop [retry-count *retry-limit*]
    (if-let [img (fetch-image url)]
      img
      (if (zero? retry-count)
	(do (warn (format "abort fetching image (%s) because reached retry limit: %d"
			  url *retry-limit*))
	    *noimg*)
	(do (.sleep TimeUnit/SECONDS 1)
	    (recur (dec retry-count)))))))

(defn- get-thumbnail [url]
  (ImageIcon. (adjust-img (get-thumbnail-aux url) 64 64)))

(defn alert-dlg [^nico.pgm.Pgm pgm extra-close-fn]
  (let [dlg (JDialog.), thumbicn (get-thumbnail (:thumbnail pgm))]
    (let [tpanel (JPanel.), dpanel (JPanel.)
	  owner (su/ifstr (:owner_name pgm) "")
	  comm_name (su/ifstr (:comm_name pgm) (:comm_id pgm))
	  olabel (JLabel. (format " %s (%s)" owner comm_name))
	  time (JLabel. (format "%s  （%d分前に開始）"
				(if (:member_only pgm) "※コミュ限" "")
				(tu/minute (tu/interval (:pubdate pgm) (tu/now)))))]
      (let [title (JLabel. (su/ifstr (:title pgm) (:id pgm)))
	    cbtn (JButton. *cicn*), layout (SpringLayout.)]
	(doto title (.setFont uu/*font*))
	(doto layout
	  (.putConstraint SpringLayout/NORTH title 0 SpringLayout/NORTH tpanel)
	  (.putConstraint SpringLayout/SOUTH title 0 SpringLayout/SOUTH tpanel)
	  (.putConstraint SpringLayout/NORTH cbtn 0 SpringLayout/NORTH tpanel)
	  (.putConstraint SpringLayout/SOUTH cbtn 0 SpringLayout/SOUTH tpanel)
	  (.putConstraint SpringLayout/WEST title 0 SpringLayout/WEST tpanel)
	  (.putConstraint SpringLayout/EAST title 0 SpringLayout/WEST cbtn)
	  (.putConstraint SpringLayout/EAST cbtn 0 SpringLayout/EAST tpanel))
	(doto cbtn
	  (add-action-listener
	   (fn [e]
	     (do-swing* :now
			(fn []
			  (.setVisible dlg false)
			  (.dispose dlg)))
	     (extra-close-fn)))
	  (.setPreferredSize (Dimension. (.getIconWidth *cicn*) (.getIconHeight *cicn*))))
	(when (:member_only pgm) (.setBackground cbtn *monly-bgcolor*))
	(doto tpanel
	  (.setPreferredSize (Dimension. 210 18))
	  (.setLayout layout) (.add title) (.add cbtn)))
      (let [thumbnail (JLabel. thumbicn),
	    desc (uu/mlabel (su/ifstr (:desc pgm) "") *desc-size*)
	    layout (GridBagLayout.), c (GridBagConstraints.)]
	(letfn [(set-con!
		 [lt component x y top left bottom right]
		 (set! (.gridx c) x) (set! (.gridy c) y) (set! (.fill c) GridBagConstraints/BOTH)
		 (set! (.insets c) (Insets. top left bottom right)) (.setConstraints lt component c))]
	  (set-con! layout thumbnail 0 0 0 0 0 0)
	  (set-con! layout desc 1 0 0 5 0 0))
	(change-cursor thumbnail (:link pgm)) (change-cursor desc (:link pgm))
	(doto dpanel
	  (.setLayout layout) (.add thumbnail) (.add desc)))
      (doto time (.setHorizontalAlignment (JLabel/RIGHT)) (.setFont uu/*font*))
      (doto olabel (.setFont uu/*font*))
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
	  (.setBackground cpane *monly-bgcolor*)
	  (.setBackground tpanel *monly-bgcolor*)
	  (.setBackground dpanel *monly-bgcolor*))
	(doto cpane
	  (.setLayout layout)
	  (.add tpanel) (.add dpanel) (.add olabel) (.add time))))
    (doto dlg
      (decorate)
      (.setFocusableWindowState false)
      (.setAlwaysOnTop true)
      (.setMinimumSize *asize*)
      (.setUndecorated true))))
