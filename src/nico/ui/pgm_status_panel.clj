;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "コントロールパネル"}
  nico.ui.pgm-status-panel
  (:use [clojure.tools.swing-utils :only [do-swing]])
  (:require [nico.db :as db]
            [nico.pgm :as pgm]
            [nico.rss-updator :as nr]
            [time-utils :as tu])
  (:import [java.awt Dimension]
           [javax.swing GroupLayout ImageIcon JPanel JLabel]
           [javax.swing.border TitledBorder]))

(def ^{:private true} PANEL-SIZE (Dimension. 350 40))

(defn pgm-status-panel []
  (let [pspanel (JPanel.)
        lpgms (JLabel. "番組数:"), vpgms (JLabel. "- / -")
        lfetched (JLabel. "最終更新:"), vfetched (JLabel. "----/--/-- --:--:--")
        layout (GroupLayout. pspanel)
        hgrp (.createSequentialGroup layout)
        vgrp (.createSequentialGroup layout)]
    (letfn [(update-status []
              (do-swing
               (.setText vpgms (if-let [qs (db/queue-size)]
                                 (if (< 0 qs)
                                   (format "%d (+ %d) / %d" (pgm/count-pgms) qs (pgm/get-total))
                                   (format "%d / %d" (pgm/count-pgms) (pgm/get-total)))
                                 "="))
               (.setText vfetched (if-let [lu (db/last-updated)]
                                    (tu/format-time-long lu)
                                    "-"))))]
      (nr/add-rss-hook :countdown (fn [count max] (update-status)))
      (db/add-db-hook :updated update-status))
    (doto hgrp
      (.addGroup (.. layout createParallelGroup
                     (addComponent lpgms) (addComponent lfetched)))
      (.addGroup (.. layout createParallelGroup
                     (addComponent vpgms) (addComponent vfetched))))
    (doto vgrp
      (.addGroup (.. layout createParallelGroup
                     (addComponent lpgms) (addComponent vpgms)))
      (.addGroup (.. layout createParallelGroup
                     (addComponent lfetched) (addComponent vfetched))))
    (doto layout
      (.setHorizontalGroup hgrp)
      (.setVerticalGroup vgrp)
      (.setAutoCreateGaps true)
      (.setAutoCreateContainerGaps true))
    (doto pspanel
      (.setPreferredSize PANEL-SIZE)
      (.setMinimumSize PANEL-SIZE)
      (.setBorder (TitledBorder. "Programs Status"))
      (.setLayout layout))))
