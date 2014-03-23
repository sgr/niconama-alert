;; -*- coding: utf-8-unix -*-
(ns nico.ui.alert-panel-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [slide.core :as slc]
            [seesaw.border :as sb]
            [seesaw.core :as sc]
            [seesaw.icon :as si])
  (:import [javax.imageio ImageIO]
           [nico.ui AlertPanel]))

(defn- wait-closing [frame]
  (let [p (promise)]
    (sc/listen frame :window-closing (fn [_] (deliver p true)))
    (-> frame
        slc/move-to-center!
        sc/show!)
    @p))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ^:gui alertpanel-test
  (let [msg "Added 10 programs to \"車載\""
        icns (map (fn [i] (ImageIO/read (io/resource "noimage.png"))) (range 10))]
    (wait-closing
     (sc/frame
      :title "testing variable AlertPanel"
      :content (AlertPanel. msg icns)
      :size [640 :by 480]
      :on-close :dispose))))

(deftest ^:gui alertpanel-fixed-test
  (let [msg "Added 10 programs to \"車載\""
        icns (map (fn [i] (ImageIO/read (io/resource "noimage.png"))) (range 10))]
    (wait-closing
     (sc/frame
      :title "testing fixed AlertPanel"
      :content (doto (AlertPanel. msg icns)
                 (.setBorder (sb/line-border :color :red))
                 (.setSize 250 80))
      :size [640 :by 480]
      :on-close :dispose))))
