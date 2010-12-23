;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "'about this application' dialog."}
  nico.ui.about-dlg
  (:use [clojure.contrib.swing-utils :only [do-swing add-action-listener]])
  (:require [nico.ui.btn :as ub])
  (:import (java.awt BorderLayout Dimension Font GridBagLayout GridBagConstraints Insets)
	   (javax.swing BorderFactory BoxLayout SpringLayout JButton JDialog JLabel JPanel)))

(def *dlg-size* (Dimension. 450 250))
(def *btn-panel-size* (Dimension. 450 40))

(defn about-dialog
  [parent title]
  (let [dlg (JDialog. parent title true), cpane (.getContentPane dlg)
	cr-panel (JPanel.), lib-panel (JPanel.), btn-panel (JPanel.), btn-ok (ub/btn "OK")
	p (.getLocationOnScreen parent)]
    (let [lapp (JLabel. "NicoNama Alert (J)")
	  lauthor (JLabel. "Copyright (C) Shigeru Fujiwara All Rights Reserved.")
	  layout (BoxLayout. cr-panel BoxLayout/Y_AXIS)]
      (.setFont lapp (Font. "Default" Font/PLAIN 20))
      (doto cr-panel
	(.setLayout layout)
	(.add lapp) (.add lauthor)))
    (let [lclj (JLabel. "Clojure 1.2.0 Copyright (c) Rich Hickey. All rights reserved.")
	  lcljc (JLabel. "Clojure-contrib 1.2.0 copyrighted by Rich Hickey and the various contributors.")
	  lsx (JLabel. "SwingX 1.6.2 SwingLabs project")
	  fnt (Font. "Default" Font/PLAIN 12)
	  layout (BoxLayout. lib-panel BoxLayout/Y_AXIS)]
      (.setFont lclj fnt) (.setFont lcljc fnt) (.setFont lsx fnt)
      (doto lib-panel
	(.setBorder (BorderFactory/createTitledBorder "Powered by"))
	(.setLayout layout) (.add lclj) (.add lcljc) (.add lsx)))
    (doto btn-ok
      (add-action-listener (fn [e] (do-swing (.setVisible dlg false) (.dispose dlg)))))
    (let [layout (SpringLayout.)]
      (doto layout
	(.putConstraint SpringLayout/NORTH btn-ok 5 SpringLayout/NORTH btn-panel)
	(.putConstraint SpringLayout/SOUTH btn-ok -10 SpringLayout/SOUTH btn-panel)
	(.putConstraint SpringLayout/EAST btn-ok -10 SpringLayout/EAST btn-panel))
      (doto btn-panel
	(.setMinimumSize *btn-panel-size*) (.setLayout layout) (.add btn-ok)))
    (.setDefaultButton (.getRootPane dlg) btn-ok)
    (let [layout (GridBagLayout.), c (GridBagConstraints.)]
      (letfn [(set-con!
	       [lt component x y top left bottom right]
	       (set! (.gridx c) x) (set! (.gridy c) y) (set! (.fill c) GridBagConstraints/BOTH)
	       (set! (.insets c) (Insets. top left bottom right)) (.setConstraints lt component c))]
	(set-con! layout cr-panel 0 0 10 40 20 40)
	(set-con! layout lib-panel 0 1 10 10 10 10)
	(set-con! layout btn-panel 0 2 0 0 0 0))
      (doto cpane
	(.setLayout layout) (.add cr-panel) (.add lib-panel) (.add btn-panel)))
    (doto dlg
      (.setLocation (+ (.x p) (int (/ (- (.getWidth parent) (.getWidth *dlg-size*)) 2)))
		    (+ (.y p) (int (/ (- (.getHeight parent) (.getHeight *dlg-size*)) 2))))
      (.setResizable false)
      (.setMinimumSize *dlg-size*))))
