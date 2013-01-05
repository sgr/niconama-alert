;; -*- coding: utf-8-unix -*-
(ns nico.core
  (:use [clojure.tools.swing-utils :only [do-swing]]
        [clojure.tools.logging]
	[nico.updator :only [start-updators]]
	[nico.ui.main-frame :only [main-frame]])
  (:require [hook-utils :as h]
            [net-utils :as n]
            [nico.prefs :as p]
            [nico.pgm :as pgm]
	    [nico.log :as l])
  (:import [javax.swing JOptionPane])
  (:gen-class))

(h/defhook main :shutdown)

(defn -main []
  ;; set up shutdown hooks
  (add-main-hook :shutdown (fn [] (pgm/shutdown)))
  (add-main-hook :shutdown (fn [] (n/clear-cache)))

  ;; call start-up functions
  (if (and (l/load-log-props)
           (p/load-pref)
           (n/init-cache)
           (pgm/init))
    (do
      ;; invoke main frame
      (let [frame (main-frame (fn [f] (add-main-hook :shutdown f))
                              (fn [] (run-main-hooks :shutdown)))]
        (do-swing (.setVisible frame true)))
      (start-updators)) ; start updators
    (do
      (error "failed starting up. shutting down...")
      (JOptionPane/showMessageDialog
       nil
       "Failed start-up procedure.\nThe cause is written the log file. \nShutting down..."
       "initializing error" JOptionPane/ERROR_MESSAGE)
      (run-main-hooks :shutdown))))
