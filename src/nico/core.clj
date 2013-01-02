;; -*- coding: utf-8-unix -*-
(ns nico.core
  (:use [clojure.tools.swing-utils :only [do-swing]]
	[nico.updator :only [start-updators]]
	[nico.ui.main-frame :only [main-frame]])
  (:require [hook-utils :as h]
            [net-utils :as n]
            [nico.prefs :as p]
            [nico.pgm :as pgm]
	    [nico.log :as l])
  (:gen-class))

(h/defhook main :shutdown)

(defn -main []
  (add-main-hook :shutdown (fn [] (pgm/shutdown)))
  (add-main-hook :shutdown (fn [] (n/clear-cache)))
  (l/load-log-props)
  (p/load-pref)
  (pgm/init-db)
  (let [frame (main-frame (fn [f] (add-main-hook :shutdown f))
                          (fn [] (run-main-hooks :shutdown)))]
    (do-swing (.setVisible frame true)))
  (start-updators))
