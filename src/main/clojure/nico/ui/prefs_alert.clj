;; -*- coding: utf-8-unix -*-
(ns nico.ui.prefs-alert
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [desktop-alert :as da]
            [seesaw.bind :as sb]
            [seesaw.core :as sc]
            [seesaw.mig :as sm]))

(def ALERT-PANEL-WIDTH 300)

(defn- btn-text [img-name]
  (format "<html><body><img src='%s'/></body></html>" (io/resource img-name)))

(defn- to-id [raw-id] (keyword (str "#" (if (keyword? raw-id) (name raw-id) raw-id))))

(defn panel
  "curr-prefs: A current alert preferences (map)
   update-fn: An updating function"
  [curr-prefs update-fn]
  (let [bgrp (sc/button-group)
        orientation-panel (sc/grid-panel
                           :columns 2 :rows 2
                           :items [(sc/radio :id :rl-tb :group bgrp :text (btn-text "rltb.png"))
                                   (sc/radio :id :rl-bt :group bgrp :text (btn-text "rlbt.png"))
                                   (sc/radio :id :lr-tb :group bgrp :text (btn-text "lrtb.png"))
                                   (sc/radio :id :lr-bt :group bgrp :text (btn-text "lrbt.png"))])
        i-column (sc/label)
        v-column (sc/slider :id :v-column :min 1 :max (da/max-columns ALERT-PANEL-WIDTH)
                            :major-tick-spacing 1 :paint-labels? true :paint-track? true)
        i-opacity (sc/label)
        v-opacity (sc/slider :id :v-opacity :min 0 :max 100
                             :major-tick-spacing 10 :paint-labels? true :paint-track? true)]
    (sb/bind
     v-column
     (sb/transform str)
     (sb/property i-column :text))

    (sb/bind
     v-opacity
     (sb/transform #(str % " [%]"))
     (sb/property i-opacity :text))

    (sb/bind
     (sb/funnel (sb/selection bgrp) v-column v-opacity)
     (sb/b-do
      [[mode column opacity]]
      (when (and mode column opacity)
        (update-fn {:mode (sc/id-of mode)
                    :column column
                    :opacity (double (/ opacity 100))}))))

    ;; initialize value
    (when-let [mode (:mode curr-prefs)]
      (sc/selection! bgrp (sc/select orientation-panel [(to-id mode)])))
    (sc/value! v-column (:column curr-prefs))
    (sc/value! v-opacity (int (* 100 (:opacity curr-prefs))))

    (sm/mig-panel
     :constraints ["wrap 3, ins 5 10 10 10, fill" "[:100:][:300:][:50:]" ""]
     :items [[(sc/label "Orientation")] [orientation-panel "grow, span 2"]
             [(sc/label "Columns")] [v-column "grow"] [i-column "left"]
             [(sc/label "Opacity")] [v-opacity "grow"] [i-opacity "left"]])))
