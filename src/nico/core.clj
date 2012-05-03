;; -*- coding: utf-8-unix -*-
(ns nico.core
  (:use [clojure.tools.swing-utils :only [do-swing]]
	[nico.updator :only [start-updators]]
	[nico.ui.main-frame :only [main-frame]])
  (:require [nico.prefs :as p]
	    [nico.log :as l])
  (:gen-class))

(defn -main []
  (l/load-log-props)
  (p/load-pref)
  (let [frame (main-frame)]
    (do-swing (.setVisible frame true)))
  (start-updators))
