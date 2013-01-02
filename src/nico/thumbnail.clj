;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "サムネイル関連画像処理"}
  nico.thumbnail
  (:use [clojure.tools.logging])
  (:require [io-utils :as io]
            [net-utils :as n])
  (:import [java.awt GraphicsEnvironment RenderingHints]
	   [java.awt.image BufferedImage]
           [java.io ByteArrayInputStream ByteArrayOutputStream]
           [javax.imageio ImageIO]))

(def NO-IMAGE (ImageIO/read (clojure.java.io/resource "noimage.png")))
(def ^{:private true} ICON-WIDTH  64)
(def ^{:private true} ICON-HEIGHT 64)

(defn adjust-img [^BufferedImage img width height]
  (let [nimg (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
        g2d (.createGraphics nimg)]
    (doto g2d
      (.setRenderingHint RenderingHints/KEY_INTERPOLATION
                         RenderingHints/VALUE_INTERPOLATION_BILINEAR)
      (.drawImage img 0 0 width height nil)
      (.dispose))
    (.flush img)
    nimg))

(let [jpeg-reader (.next (ImageIO/getImageReadersByFormatName "jpeg"))
      jpeg-writer (.next (ImageIO/getImageWritersByFormatName "jpeg"))
      use-cache (do (ImageIO/setUseCache false) (ImageIO/getUseCache))]
  (defn to-bytes [^java.awt.Image img]
    (let [baos (ByteArrayOutputStream.)
          ios (ImageIO/createImageOutputStream baos)]
      (try
        (doto jpeg-writer (.setOutput ios) (.write img))
        (.toByteArray baos)
        (finally
          (.flush ios)
          (.reset jpeg-writer)
          (.close ios)
          (.close baos)))))

  (defn fetch [url]
    (debug (format "called thumbnail/fetch with %s" url))
    (let [is (n/url-stream-with-caching url)
          iis (if is (ImageIO/createImageInputStream is) nil)
          img (if iis (do (.setInput jpeg-reader iis)
                          (.read jpeg-reader 0))
                  nil)]
      (try
        (if img (adjust-img img ICON-WIDTH ICON-HEIGHT) NO-IMAGE)
        (catch Exception e (error e (format "failed fetching image: %s" url)) NO-IMAGE)
        (finally (when img (.flush img))
                 (when jpeg-reader (.reset jpeg-reader))
                 (when iis (.close iis))
                 (when is (.close is)))))))
