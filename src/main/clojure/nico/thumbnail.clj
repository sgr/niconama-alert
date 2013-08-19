;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "サムネイル関連画像処理"}
  nico.thumbnail
  (:use [clojure.tools.logging])
  (:require [io-utils :as io]
            [net-utils :as n])
  (:import [java.awt GraphicsEnvironment Image RenderingHints]
	   [java.awt.image BufferedImage RenderedImage]
           [java.io ByteArrayInputStream ByteArrayOutputStream]
           [javax.imageio ImageIO ImageReader ImageWriter]
           [javax.imageio.stream ImageInputStream]
           [javax.swing ImageIcon]))

(def ^{:private true} NO-IMAGE (ImageIO/read (clojure.java.io/resource "noimage.png")))

(defn ^BufferedImage adjust-img [^BufferedImage img width height]
  (let [nimg (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
        g2d (.createGraphics nimg)]
    (try
      (doto g2d
        (.setRenderingHint RenderingHints/KEY_INTERPOLATION RenderingHints/VALUE_INTERPOLATION_BILINEAR)
        (.drawImage img 0 0 width height nil))
      nimg
      (finally
        (.dispose g2d)
        (.flush img)))))

(let [use-cache (do (ImageIO/setUseCache false) (ImageIO/getUseCache))
      ^ImageReader jpeg-reader (.next (ImageIO/getImageReadersByFormatName "jpeg"))
      ^ImageWriter jpeg-writer (.next (ImageIO/getImageWritersByFormatName "jpeg"))]
  (defn fetch [url width height]
    (letfn [(to-bytes [^Image img]
              (let [baos (ByteArrayOutputStream.)
                    ios (ImageIO/createImageOutputStream baos)]
                (try
                  (doto jpeg-writer (.setOutput ios) (.write ^RenderedImage img))
                  (.toByteArray baos)
                  (finally
                    (.flush ios)
                    (.reset jpeg-writer)
                    (.close ios)
                    (.close baos)))))
            (read-img-from-response [response]
              (let [is (-> response .getEntity .getContent)
                    ^ImageInputStream iis (if is (ImageIO/createImageInputStream is) nil)]
                (try
                  (if iis
                    (do (.setInput jpeg-reader iis true true)
                        (.read jpeg-reader 0))
                    nil)
                  (catch Exception e
                    (error e (format "failed reading thumbnail image from response stream: %s" url)))
                  (finally (when jpeg-reader (.reset jpeg-reader))
                           (when iis (.close iis))
                           (when is (.close is))))))]
    (locking jpeg-reader
      (let [^BufferedImage img (n/fetch-with-caching url read-img-from-response)]
        (try
          (ImageIcon. (adjust-img (or img NO-IMAGE) width height))
          (catch Exception e
            (error e (format "failed fetching image: %s" url))
            (ImageIcon. ^Image NO-IMAGE))
          (finally (when img (.flush img)))))))))
