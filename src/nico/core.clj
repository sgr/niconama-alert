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

(defn- startup []
  (l/load-log-props)
  (p/load-pref)
  (n/init-cache)
  (pgm/init))

(defn -main []
  ;; set up shutdown hooks
  (add-main-hook :shutdown (fn [] (pgm/shutdown)))
  (add-main-hook :shutdown (fn [] (n/clear-cache)))

  ;; call start-up functions
  (try
    (l/load-log-props)
    (p/load-pref)
    (n/init-cache)
    (pgm/init)
    ;; invoke main frame
    (let [frame (main-frame (fn [f] (add-main-hook :shutdown f))
                            (fn [] (run-main-hooks :shutdown)))]
      (do-swing (.setVisible frame true)))
    (start-updators) ; start updators
    (catch Exception e
      (error e (format "failed starting up"))
      (JOptionPane/showMessageDialog
       nil
       (format "Failed start-up procedure.\nCause: %s\nShutting down..." (.getMessage e))
       "initializing error" JOptionPane/ERROR_MESSAGE)
      (run-main-hooks :shutdown))))
