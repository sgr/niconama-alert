;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "'about this application' dialog."}
  nico.ui.about-dlg
  (:use [clojure.tools.swing-utils :only [do-swing add-action-listener]])
  (:require [nico.ui.util :as uu]
	    [nico.ui.env-panel :as ue])
  (:import [java.awt BorderLayout Dimension Font]
	   [javax.swing BorderFactory BoxLayout SpringLayout
			JButton JDialog JLabel JPanel JTabbedPane]))

(def ^{:private true} DLG-SIZE (Dimension. 500 300))
(def ^{:private true} CR-PANEL-SIZE (Dimension. 450 80))
(def ^{:private true} BTN-PANEL-SIZE (Dimension. 450 40))
(def ^{:private true} APP-NAME-FONT (Font. "Default" Font/PLAIN 20))

(defn- about-panel []
  (let [cr-panel (JPanel.) lib-panel (JPanel.)]
    (let [lapp (JLabel. "NicoNama Alert (J)")
	  lauthor (JLabel. "Copyright (C) Shigeru Fujiwara All Rights Reserved.")
	  layout (SpringLayout.)]
      (doto lapp (.setFont APP-NAME-FONT))
      (doto lauthor (.setFont uu/DEFAULT-FONT))
      (doto layout
	(.putConstraint SpringLayout/NORTH lapp 20 SpringLayout/NORTH cr-panel)
	(.putConstraint SpringLayout/WEST lapp 20 SpringLayout/WEST cr-panel)
	(.putConstraint SpringLayout/NORTH lauthor 10 SpringLayout/SOUTH lapp)
	(.putConstraint SpringLayout/EAST lauthor -30 SpringLayout/EAST cr-panel)
	(.putConstraint SpringLayout/SOUTH lauthor -10 SpringLayout/SOUTH cr-panel))
      (doto cr-panel
	(.setLayout layout)
	(.setPreferredSize CR-PANEL-SIZE)
	(.add lapp) (.add lauthor)))
    (let [inner-panel (JPanel.)
	  lclj (uu/mlabel "Clojure 1.4.0 Copyright (c) Rich Hickey. All rights reserved.")
	  lcljc (uu/mlabel
		 "Clojure-contrib 1.2.0 copyrighted by Rich Hickey and the various contributors.")
	  lsx (uu/mlabel "Enlive 1.0.0 Copyright (c) Christophe Grand, 2009. All rights reserved.")
	  layout (BoxLayout. inner-panel BoxLayout/Y_AXIS)]
      (doto lclj (.setFont uu/DEFAULT-FONT))
      (doto lcljc (.setFont uu/DEFAULT-FONT))
      (doto lsx (.setFont uu/DEFAULT-FONT))
      (doto inner-panel
	(.setBorder (BorderFactory/createTitledBorder "Powered by"))
	(.setLayout layout) (.add lclj) (.add lcljc) (.add lsx))
      (doto lib-panel
	(uu/do-add-expand inner-panel 5)))
    (doto (JPanel.)
      (.setLayout (BorderLayout.))
      (.add cr-panel BorderLayout/NORTH)
      (.add lib-panel BorderLayout/CENTER))))

(defn about-dialog
  [parent title]
  (let [dlg (JDialog. parent title true), cpane (.getContentPane dlg)
	tpane (JTabbedPane.) btn-panel (JPanel.)
	p (.getLocationOnScreen parent)]
    (let [btn-ok (uu/btn "OK")]
      (doto btn-ok
	(add-action-listener (fn [e] (do-swing (.setVisible dlg false) (.dispose dlg)))))
      (.setDefaultButton (.getRootPane dlg) btn-ok)
      (let [layout (SpringLayout.)]
	(doto layout
	  (.putConstraint SpringLayout/NORTH btn-ok 5 SpringLayout/NORTH btn-panel)
	  (.putConstraint SpringLayout/SOUTH btn-ok -10 SpringLayout/SOUTH btn-panel)
	  (.putConstraint SpringLayout/EAST btn-ok -10 SpringLayout/EAST btn-panel))
	(doto btn-panel
	  (.setPreferredSize BTN-PANEL-SIZE)
	  (.setLayout layout) (.add btn-ok))))
    (doto tpane
      (.add "About" (about-panel))
      (.add "Your Environment" (ue/env-panel)))
    (doto cpane
      (.add tpane BorderLayout/CENTER)
      (.add btn-panel BorderLayout/SOUTH))
    (doto dlg
      (.setLocation (+ (.x p) (int (/ (- (.getWidth parent) (.getWidth DLG-SIZE)) 2)))
		    (+ (.y p) (int (/ (- (.getHeight parent) (.getHeight DLG-SIZE)) 2))))
      (.setResizable false)
      (.setMinimumSize DLG-SIZE))))
