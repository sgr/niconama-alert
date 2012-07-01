;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "'about this application' dialog."}
  nico.ui.about-dlg
  (:use [clojure.tools.swing-utils :only [do-swing add-action-listener]])
  (:require [clojure.string :as s]
            [nico.ui.util :as uu]
	    [nico.ui.env-panel :as ue])
  (:import [java.awt BorderLayout Dimension Font]
	   [javax.swing BorderFactory BoxLayout SpringLayout
			JButton JDialog JLabel JPanel JScrollPane JTabbedPane]))

(def ^{:private true} DLG-SIZE (Dimension. 500 300))
(def ^{:private true} CR-PANEL-SIZE (Dimension. 450 55))
(def ^{:private true} BTN-PANEL-SIZE (Dimension. 450 40))
(def ^{:private true} APP-NAME-FONT (Font. "Default" Font/PLAIN 20))

(def ^{:private true} POWERED-BY
  (s/join \newline
          ["Clojure 1.4.0 Copyright (c) Rich Hickey. All rights reserved."
           "clojure.data.zip 0.1.1 Copyright (c) Rich Hickey and contributors. All rights reserved."
           "clojure.java.jdbc 0.2.3 Copyright (c) Stephen Gilardi, Sean Corfield, 2011-2012. All rights reserved."
           "clojure.math.numeric-tower 0.0.1"
           "clojure.tools.logging 0.2.3 Copyright (c) 2009 Alex Taggart"
           "clj-http 0.4.3 authored by mmcgrana and dakrone"
           "swing-utils 0.2.0 Copyright (c) Stephen C. Gilardi and Meikel Brandmeyer. All rights reserved."
           "Enlive 1.0.1 Copyright (c) Christophe Grand, 2012. All rights reserved."
           "c3p0 0.9.1.2 Copyright (c) 2012 Machinery For Change, Inc."
           "H2 Database Engine 1.3"
           "... and great dependencies"]))

(defn- about-panel []
  (let [cr-panel (JPanel.) lib-panel (JPanel.)]
    (let [lapp (JLabel. "NicoNama Alert (J)")
	  lauthor (JLabel. "Copyright (C) Shigeru Fujiwara All Rights Reserved.")
	  layout (SpringLayout.)]
      (doto lapp (.setFont APP-NAME-FONT))
      (doto lauthor (.setFont uu/DEFAULT-FONT))
      (doto layout
	(.putConstraint SpringLayout/NORTH lapp 10 SpringLayout/NORTH cr-panel)
	(.putConstraint SpringLayout/WEST lapp 20 SpringLayout/WEST cr-panel)
	(.putConstraint SpringLayout/NORTH lauthor 5 SpringLayout/SOUTH lapp)
	(.putConstraint SpringLayout/EAST lauthor -10 SpringLayout/EAST cr-panel)
	(.putConstraint SpringLayout/SOUTH lauthor -3 SpringLayout/SOUTH cr-panel))
      (doto cr-panel
	(.setLayout layout)
	(.setPreferredSize CR-PANEL-SIZE)
	(.add lapp) (.add lauthor)))
    (let [inner-panel (JPanel.)
	  lpowered-by (uu/mlabel POWERED-BY)
	  layout (BoxLayout. inner-panel BoxLayout/Y_AXIS)]
      (doto lpowered-by (.setFont uu/DEFAULT-FONT))
      (doto inner-panel
	(.setBorder (BorderFactory/createTitledBorder "Powered by"))
	(.setLayout layout) (.add (JScrollPane. lpowered-by)))
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
