;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "コントロールパネル"}
  nico.ui.control-panel
  (:use [clojure.contrib.swing-utils :only [do-swing add-action-listener]]
	[nico.ui.api-panel :only [api-panel]]
	[nico.ui.pgm-status-panel :only [pgm-status-panel]]
	[nico.ui.rss-panel :only [rss-panel]])
  (:require [nico.pgm :as pgm]
	    [nico.rss :as rss]
	    [nico.updator :as nu]
	    [time-utils :as tu])
  (:import (java.awt BorderLayout Dimension)
	   (javax.swing ImageIcon JPanel JButton JProgressBar JLabel
			SpringLayout GroupLayout)))

(def *size* (Dimension. 500 90))

(defn control-panel []
  (let [panel (JPanel.)
	ppanel (pgm-status-panel)
	apanel (api-panel)
	rpanel (rss-panel)
	layout (SpringLayout.)]
    (doto layout
      (.putConstraint SpringLayout/NORTH ppanel 1 SpringLayout/NORTH panel)
      (.putConstraint SpringLayout/SOUTH ppanel -1 SpringLayout/SOUTH panel)
      (.putConstraint SpringLayout/NORTH apanel 1 SpringLayout/NORTH panel)
      (.putConstraint SpringLayout/SOUTH apanel -1 SpringLayout/SOUTH panel)
      (.putConstraint SpringLayout/NORTH rpanel 1 SpringLayout/NORTH panel)
      (.putConstraint SpringLayout/SOUTH rpanel -1 SpringLayout/SOUTH panel)
      (.putConstraint SpringLayout/WEST ppanel 1 SpringLayout/WEST panel)
      (.putConstraint SpringLayout/EAST ppanel -1 SpringLayout/WEST apanel)
      (.putConstraint SpringLayout/EAST apanel -1 SpringLayout/WEST rpanel)
      (.putConstraint SpringLayout/EAST rpanel -1 SpringLayout/EAST panel))
    (doto panel
      (.setPreferredSize *size*)
      (.add apanel)
      (.add ppanel)
      (.add rpanel)
      (.setLayout layout))))