;; -*- coding: utf-8-unix -*-
(ns nico.ui.util
  "Mac OSのJREでSystemのLook & Feelを長時間使っていると異常にCPUを食うようになるのでその対策"
  (:require [config-file :as cf]
            [seesaw.core :as sc])
  (:import [javax.swing JCheckBox JProgressBar UIManager]
           [javax.swing.plaf.basic BasicCheckBoxUI BasicProgressBarUI]
           [javax.swing.plaf.metal MetalCheckBoxUI]))

(defn progress-bar []
  (let [pbar (JProgressBar.)]
    (when (= :mac (cf/system))
      (let [fg (UIManager/getColor "List.selectionForeground")
            bg (UIManager/getColor "List.selectionBackground")]
        (.setUI pbar (proxy [BasicProgressBarUI] []
                       (getSelectionBackground [] bg)
                       (getSelectionForeground [] fg)))
        (.setForeground pbar bg)))
    (doto pbar
      (sc/config! :id :rss-progress)
      (.setStringPainted true)
      (.setString ""))))

(let [font (UIManager/getFont "Button.font")
      icon-checked (sc/icon "cb_checked.png")
      icon-unchecked (sc/icon "cb_unchecked.png")]
  (defn checkbox [& args]
    (let [cb (apply sc/checkbox args)]
      (when (= :mac (cf/system))
        (.setIcon cb icon-unchecked)
        (.setSelectedIcon cb icon-checked)
        (.setFont cb font))
      cb)))
