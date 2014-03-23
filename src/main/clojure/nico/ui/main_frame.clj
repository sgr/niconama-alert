;; -*- coding: utf-8-unix -*-
(ns nico.ui.main-frame
  (:require [clojure.tools.logging :as log]
            [nico.config :as config]
            [nico.ui.search-panel :as spanel]
            [seesaw.core :as sc]
            [seesaw.mig :as sm])
  (:import [java.awt.event WindowEvent]
           [nico.ui WaterfallPanel]))

(defn close! [window]
  (.dispatchEvent window (WindowEvent. window WindowEvent/WINDOW_CLOSING)))

(defn frame []
  (let [api-btn (sc/button :id :api-btn :icon "start.png" :enabled? false)
        rss-btn (sc/button :id :rss-btn :icon "start.png")
        status-panel (sm/mig-panel
                      :border "Programs status"
                      :constraints ["fill, ins 0 10 5 10"]
                      :items [[(sc/label :id :l-npgms :text "No program")]
                              [(sc/label :id :l-last-updated :text "Last updated: -")]])
        api-panel (sm/mig-panel
                   :border "API listening status"
                   :constraints ["wrap 3, fill, ins 0 10 5 10"]
                   :items [[(sc/label :id :api-status :text "idle") "grow"]
                           [(sc/label :id :api-rate :text "- programs/min") "grow"]
                           [api-btn "spany 2, align right"]])
        rss-panel (sm/mig-panel
                   :border "RSS fetching status"
                   :constraints ["wrap 3, fill, ins 0 10 5 10"]
                   :items [[(sc/label :id :rss-status :text "stand-by") "grow"]
                           [(doto (sc/progress-bar :id :rss-progress :paint-string? true)
                              (.setString "")) "grow"]
                           [rss-btn "spany 2, align right"]])
        cpanel (sm/mig-panel
                :constraints ["wrap 3, fill, ins 0 10 10 10" "[33%][33%][34%]"]
                :items [[status-panel "grow"][api-panel "grow"][rss-panel "grow"]])
        tpanel (sc/tabbed-panel :id :tabbed-panel :user-data {}
                                :tabs [{:title "Listings" :tip "Nico program listings"
                                        :content (sc/scrollable (sc/config! (WaterfallPanel.) :id :wpanel))}
                                       {:title "Search" :tip "Search programs"
                                        :content (sc/config! (spanel/panel nil) :id :spanel)}])
        content (sc/border-panel :center tpanel :south cpanel)
        frame (sc/frame :title config/APP-TITLE
                        :minimum-size [800 :by 450]
                        :size [800 :by 600]
                        :content content
                        :on-close :dispose)]
    frame))
