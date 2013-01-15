;; -*- coding: utf-8-unix -*-
(ns nico.core
  (:use [clojure.tools.swing-utils :only [do-swing]]
        [clojure.tools.logging]
        [nico.updator :only [start-updators]]
        [nico.ui.main-frame :only [main-frame]])
  (:require [hook-utils :as h]
            [net-utils :as n]
            [nico.db :as db]
            [nico.log :as l]
            [nico.prefs :as p])
  (:import [java.lang.management ManagementFactory]
           [javax.swing JOptionPane])
  (:gen-class))

(h/defhook main :shutdown)

(defn -main []
  ;; set up shutdown hooks
  (add-main-hook :shutdown (fn [] (db/shutdown)))
  (add-main-hook :shutdown (fn [] (n/clear-cache)))

  ;; call start-up functions
  (try
    (l/load-log-props)
    (p/load-pref)
    (n/init-cache)
    (db/init)
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

(let [mbean (ManagementFactory/getMemoryMXBean)
      husage #(.getHeapMemoryUsage mbean)
      nusage #(.getNonHeapMemoryUsage mbean)
      usage-map (fn [^java.lang.management.MemoryUsage u]
                  {:init (.getInit u) :used (.getUsed u) :committed (.getCommitted u) :max (.getMax u)})
      log-memory-usage #(let [h (usage-map (husage))
                              n (usage-map (nusage))]
                          (debug (format "JVM heap usage:     init(%d), used(%d) ->     heap delta: %d  "
                                         (:init h) (:used h) (- (:used h) (:init h))))
                          (debug (format "JVM non-heap usage: init(%d), used(%d) -> non-heap delta: %d"
                                         (:init n) (:used n) (- (:used n) (:init n)))))
      listener (proxy [javax.management.NotificationListener][]
                     (handleNotification [^javax.management.Notification notif handback]
                       (when (= (.getType notif)
                                java.lang.management.MemoryNotificationInfo/MEMORY_THRESHOLD_EXCEEDED)
                         (debug "calling GC!")
                         (log-memory-usage)
                         (.gc mbean)
                         (log-memory-usage))))]
;;  (.addNotificationListener ^javax.management.NotificationEmitter mbean listener nil nil)
  (db/add-db-hook :updated (fn []
                             (log-memory-usage)
                             (.gc mbean)
                             (log-memory-usage))))
