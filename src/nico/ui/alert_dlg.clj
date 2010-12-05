;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Alert dialog."}
  nico.ui.alert-dlg
  (:use [clojure.contrib.swing-utils :only [add-action-listener do-swing*]])
  (:require [time-utils :as tu])
  (:import (java.awt Desktop Dimension FlowLayout GraphicsEnvironment RenderingHints
		     GridBagLayout GridBagConstraints Insets)
	   (java.awt.event ComponentAdapter MouseListener MouseMotionListener)
	   (java.awt.geom RoundRectangle2D$Float)
	   (java.awt.image BufferedImage)
	   (java.net URI URL)
	   (javax.swing BorderFactory ImageIcon JButton JDialog JLabel JPanel JTextArea SpringLayout)
	   (javax.swing.text.html HTMLEditorKit)
	   (javax.imageio ImageIO)))

(def *asize* (Dimension. 220 130))
(def *lcsr* (.getLinkCursor (HTMLEditorKit.)))
(def *cicn* (ImageIcon. (.getResource (.getClassLoader (class (fn []))) "closebtn.png")))
(def *noimg* (ImageIO/read (.getResource (.getClassLoader (class (fn []))) "noimage.png")))

(defn dlg-width [] (.width *asize*))
(defn dlg-height [] (.height *asize*))

(defn- mlabel [col text]
  (let [l (JTextArea. text)]
    (doto l
      (.setColumns col)
      (.setOpaque false) (.setEditable false) (.setFocusable false) (.setLineWrap true))))

(defn- change-cursor [c l]
  (let [csr (.getCursor c)]
    (doto c
      (.addMouseListener (proxy [MouseListener][]
			   (mouseEntered [e] (.setCursor (.getSource e) *lcsr*))
			   (mouseExited [e] (.setCursor (.getSource e) csr))
			   (mousePressed [e] (.browse (Desktop/getDesktop) (URI. l)))
			   (mouseClicked [e]) (mouseReleased [e]))))))

(defn- adjust-img [img width height]
  (let [nimg (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
	g2d (.createGraphics nimg)]
    (doto g2d
      (.setRenderingHint RenderingHints/KEY_INTERPOLATION RenderingHints/VALUE_INTERPOLATION_BILINEAR)
      (.drawImage img 0 0 width height nil))
    nimg))

(defn- get-thumbnail [url]
  (ImageIcon. (adjust-img (try
			    (ImageIO/read url)
			    (catch Exception _ *noimg*))
    64 64)))

(defn alert-dlg [pgm extra-close-fn]
  (let [dlg (JDialog.), thumbicn (get-thumbnail (URL. (:thumbnail pgm)))]
    (let [tpanel (JPanel.), dpanel (JPanel.)
	  owner (if-let [n (:owner_name pgm)] n "## NO_OWNER ##")
	  comm_name (if-let [n (:comm_name pgm)] n "## NO_COMMUNITY ##")
	  olabel (JLabel. (format " %s (%s)" owner comm_name))
	  time (JLabel. (format "%s  （%d分前に開始）"
				(if (:member_only pgm) "※コミュ限" "")
				(tu/minute (tu/interval (:pubdate pgm) (tu/now)))))]
      (let [title (JLabel. (if-let [t (:title pgm)] t "## NO_TITLE ##"))
	    cbtn (JButton. *cicn*), layout (SpringLayout.)]
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
	(doto tpanel
	  (.setPreferredSize (Dimension. 210 18))
	  (.setLayout layout) (.add title) (.add cbtn)))
      (let [thumbnail (JLabel. thumbicn), desc (mlabel 12 (if-let [d (:desc pgm)] d "## NO_DESC ##"))
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
      (doto time (.setHorizontalAlignment (JLabel/RIGHT)))
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
	(doto cpane
	  (.setLayout layout)
	  (.add tpanel) (.add dpanel) (.add olabel) (.add time))))
    (try
      (when (com.sun.awt.AWTUtilities/isTranslucencySupported com.sun.awt.AWTUtilities$Translucency/TRANSLUCENT)
	(com.sun.awt.AWTUtilities/setWindowOpacity dlg 0.9))
      (doto dlg
	(.addComponentListener
	 (proxy [ComponentAdapter][]
	   (componentResized
	    [e]
	    (try
	      (com.sun.awt.AWTUtilities/setWindowShape
	       dlg (RoundRectangle2D$Float. 0 0 (.getWidth dlg) (.getHeight dlg) 20 20))
	      (catch Exception _ (println "This platform doesn't support AWTUtilities/setWindowShape.")))))))
      (catch Exception e (println "Unknown Exception raised") (.printStackTrace e))
      (catch NoClassDefFoundError _ (println "This platform doesn't support AWTUtilities/setWindowOpacity.")))
    (doto dlg
      (.setFocusableWindowState false)
      (.setAlwaysOnTop true)
      (.setMinimumSize *asize*)
      (.setUndecorated true))))
