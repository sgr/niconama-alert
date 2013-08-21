;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "サムネイル関連画像処理"}
  nico.thumbnail
  (:import [java.awt Image]
           [javax.imageio ImageIO]
           [javax.swing ImageIcon]
           [com.github.sgr.util ImageCache]))

(def ^{:private true} NO-IMAGE (ImageIO/read (clojure.java.io/resource "noimage.png")))

(let [image-cache (ImageCache. 512 NO-IMAGE)]
  (defn fetch [url width height]
    (let [^Image img (.getImage image-cache url)]
      (ImageIcon. (.getScaledInstance img width height Image/SCALE_SMOOTH)))))
