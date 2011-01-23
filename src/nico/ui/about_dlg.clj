;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "'about this application' dialog."}
  nico.ui.about-dlg
  (:use [clojure.contrib.swing-utils :only [do-swing add-action-listener]])
  (:require [nico.ui.util :as uu])
  (:import (java.awt BorderLayout Dimension Font GridBagLayout GridBagConstraints Insets)
	   (javax.swing BorderFactory BoxLayout SpringLayout JButton JDialog JLabel JPanel)))

(def *dlg-size* (Dimension. 450 250))
(def *cr-panel-size* (Dimension. 450 80))
(def *btn-panel-size* (Dimension. 450 40))
(def *app-name-font* (Font. "Default" Font/PLAIN 20))

(defn about-dialog
  [parent title]
  (let [dlg (JDialog. parent title true), cpane (.getContentPane dlg)
	cr-panel (JPanel.), lib-panel (JPanel.), btn-panel (JPanel.), btn-ok (uu/btn "OK")
	p (.getLocationOnScreen parent)]
    (let [lapp (JLabel. "NicoNama Alert (J)")
	  lauthor (JLabel. "Copyright (C) Shigeru Fujiwara All Rights Reserved.")
	  layout (SpringLayout.)]
      (doto lapp (.setFont *app-name-font*))
      (doto lauthor (.setFont uu/*font*))
      (doto layout
	(.putConstraint SpringLayout/NORTH lapp 20 SpringLayout/NORTH cr-panel)
	(.putConstraint SpringLayout/WEST lapp 20 SpringLayout/WEST cr-panel)
	(.putConstraint SpringLayout/NORTH lauthor 10 SpringLayout/SOUTH lapp)
	(.putConstraint SpringLayout/EAST lauthor -30 SpringLayout/EAST cr-panel)
	(.putConstraint SpringLayout/SOUTH lauthor -10 SpringLayout/SOUTH cr-panel))
      (doto cr-panel
	(.setLayout layout)
	(.setPreferredSize *cr-panel-size*)
	(.add lapp) (.add lauthor)))
    (let [inner-panel (JPanel.)
	  lclj (uu/mlabel "Clojure 1.2.0 Copyright (c) Rich Hickey. All rights reserved.")
	  lcljc (uu/mlabel "Clojure-contrib 1.2.0 copyrighted by Rich Hickey and the various contributors.")
	  lsx (uu/mlabel "SwingX 1.6.2 SwingLabs project")
	  layout (BoxLayout. inner-panel BoxLayout/Y_AXIS)]
      (doto lclj (.setFont uu/*font*))
      (doto lcljc (.setFont uu/*font*))
      (doto lsx (.setFont uu/*font*))
      (doto inner-panel
	(.setBorder (BorderFactory/createTitledBorder "Powered by"))
	(.setLayout layout) (.add lclj) (.add lcljc) (.add lsx))
      (doto lib-panel
	(uu/do-add-expand inner-panel 5)))
    (doto btn-ok
      (add-action-listener (fn [e] (do-swing (.setVisible dlg false) (.dispose dlg)))))
    (let [layout (SpringLayout.)]
      (doto layout
	(.putConstraint SpringLayout/NORTH btn-ok 5 SpringLayout/NORTH btn-panel)
	(.putConstraint SpringLayout/SOUTH btn-ok -10 SpringLayout/SOUTH btn-panel)
	(.putConstraint SpringLayout/EAST btn-ok -10 SpringLayout/EAST btn-panel))
      (doto btn-panel
	(.setPreferredSize *btn-panel-size*)
	(.setLayout layout) (.add btn-ok)))
    (.setDefaultButton (.getRootPane dlg) btn-ok)
    (doto cpane
      (.add cr-panel BorderLayout/NORTH)
      (.add lib-panel BorderLayout/CENTER)
      (.add btn-panel BorderLayout/SOUTH))
    (doto dlg
      (.setLocation (+ (.x p) (int (/ (- (.getWidth parent) (.getWidth *dlg-size*)) 2)))
		    (+ (.y p) (int (/ (- (.getHeight parent) (.getHeight *dlg-size*)) 2))))
      (.setResizable false)
      (.setMinimumSize *dlg-size*))))
