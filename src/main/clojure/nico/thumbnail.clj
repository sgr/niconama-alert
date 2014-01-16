;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "サムネイル関連画像処理"}
  nico.thumbnail
  (:import [java.awt Image]
           [javax.imageio ImageIO]
           [javax.swing ImageIcon]
           [nico.cache ImageCache]))

(let [no-image (ImageIO/read (clojure.java.io/resource "noimage.png"))
      image-cache (ImageCache. 2048 150 150 no-image)]
  (defn fetch [url width height]
    (let [^Image img (.getImage image-cache url)]
      (ImageIcon. (.getScaledInstance img width height Image/SCALE_SMOOTH)))))
