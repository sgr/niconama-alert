;; -*- coding: utf-8-unix -*-
(ns nico.ui.main-frame
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [config-file :as cf]
            [nico.config :as config]
            [nico.ui.search-panel :as spanel]
            [nico.ui.util :as util]
            [seesaw.border :as sb]
            [seesaw.core :as sc])
  (:import [java.awt Color Toolkit Window]
           [java.awt.event WindowEvent]
           [javax.swing JPanel SpringLayout]
           [nico.ui WaterfallPanel]))

(defn close! [^Window window]
  (.dispatchEvent window (WindowEvent. window WindowEvent/WINDOW_CLOSING)))

(defn- api-panel [api-btn]
  (let [p (JPanel.)
        l (SpringLayout.)
        status (sc/label :id :api-status :text "idle")
        rate (sc/label :id :api-rate :text "No programs/min" :halign :center)]
    (.putConstraint l SpringLayout/WEST status 15 SpringLayout/WEST p)
    (.putConstraint l SpringLayout/WEST rate 30 SpringLayout/EAST status)
    (.putConstraint l SpringLayout/EAST api-btn -15 SpringLayout/EAST p)
    (.putConstraint l SpringLayout/EAST rate -30 SpringLayout/WEST api-btn)
    (.putConstraint l SpringLayout/NORTH p -5 SpringLayout/NORTH api-btn)
    (.putConstraint l SpringLayout/SOUTH p 5 SpringLayout/SOUTH api-btn)
    (.putConstraint l SpringLayout/VERTICAL_CENTER status 0 SpringLayout/VERTICAL_CENTER p)
    (.putConstraint l SpringLayout/VERTICAL_CENTER rate 0 SpringLayout/VERTICAL_CENTER p)
    (doto p
      (.setBorder (sb/to-border "API listening status"))
      (.setLayout l)
      (.add status)
      (.add rate)
      (.add api-btn))))

(defn- rss-panel [rss-btn]
  (let [p (JPanel.)
        l (SpringLayout.)
        status (sc/label :id :rss-status :text "stand-by")
        pbar (util/progress-bar)]
    (.putConstraint l SpringLayout/WEST status 15 SpringLayout/WEST p)
    (.putConstraint l SpringLayout/WEST pbar 30 SpringLayout/EAST status)
    (.putConstraint l SpringLayout/EAST rss-btn -15 SpringLayout/EAST p)
    (.putConstraint l SpringLayout/EAST pbar -30 SpringLayout/WEST rss-btn)
    (.putConstraint l SpringLayout/NORTH p -5 SpringLayout/NORTH rss-btn)
    (.putConstraint l SpringLayout/SOUTH p 5 SpringLayout/SOUTH rss-btn)
    (.putConstraint l SpringLayout/VERTICAL_CENTER status 0 SpringLayout/VERTICAL_CENTER p)
    (.putConstraint l SpringLayout/VERTICAL_CENTER pbar 0 SpringLayout/VERTICAL_CENTER p)
    (doto p
      (.setBorder (sb/to-border "RSS fetching status"))
      (.setLayout l)
      (.add status)
      (.add pbar)
      (.add rss-btn))))

(defn frame []
  (let [api-btn (sc/button :id :api-btn :icon "start.png" :enabled? false)
        rss-btn (sc/button :id :rss-btn :icon "start.png")
        status-panel (sc/grid-panel
                      :rows 1 :columns 2 :hgap 30
                      :border "Programs status"
                      :items [(sc/label :id :l-npgms :text "No program" :halign :center)
                              (sc/label :id :l-last-updated :text "Last updated: -" :halign :center)])
        api-panel (api-panel api-btn)
        rss-panel (rss-panel rss-btn)
        cpanel (sc/grid-panel
                :rows 1 :columns 3 :hgap 10
                :border (sb/empty-border :top 5 :bottom 15 :left 10 :right 10)
                :items [status-panel api-panel rss-panel])
        tpanel (sc/tabbed-panel :id :tabbed-panel :user-data {}
                                :tabs [{:title "Listings" :tip "Nico program listings"
                                        :content (-> (WaterfallPanel.) (sc/config! :id :wpanel) sc/scrollable)}
                                       {:title "Search" :tip "Search programs"
                                        :content (-> (spanel/panel nil) (sc/config! :id :spanel))}])
        content (sc/border-panel :center tpanel :south cpanel)
        frame (sc/frame :title config/APP-TITLE
                        :minimum-size [800 :by 450]
                        :size [800 :by 600]
                        :content content
                        :on-close :dispose)]
    (when (not= :mac (cf/system))
      (let [tk (Toolkit/getDefaultToolkit)]
        (.setIconImages
         frame
         (map #(.createImage tk (io/resource %))
              ["icon_16x16.png"
               "icon_32x32.png"
               "icon_64x64.png"
               "icon_128x128.png"]
              ))))
    frame))
