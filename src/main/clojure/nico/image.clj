;; -*- coding: utf-8-unix -*-
(ns nico.image
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [nico.net :as net])
  (:use [slingshot.slingshot :only [try+]])
  (:import [java.awt Image Container MediaTracker Toolkit]
           [java.io InputStream ByteArrayInputStream ByteArrayOutputStream]
           [java.util LinkedHashMap]
           [javax.imageio ImageIO]
           [nico.ui PgmPanelLayout]))

(defn- bytes-from-is [^InputStream is buf-size]
  (with-open [baos (ByteArrayOutputStream.)]
    (let [ba (byte-array buf-size)]
      (loop [n (.read is ba 0 buf-size)]
        (when (pos? n)
          (.write baos ba 0 n)
          (recur (.read is ba 0 buf-size))))
      (.toByteArray baos))))

(defn- fetch-bytes [^String url]
  (bytes-from-is (-> url (net/http-get {:as :stream}) :body) 1024))

(let [tk (Toolkit/getDefaultToolkit)
      dummy-component (Container.)]
  (defn- image-from-bytes-tk [bs]
    (when bs
      (let [img (.createImage tk bs)
            mt (MediaTracker. dummy-component)]
        (try
          (.addImage mt img 1)
          (.waitForAll mt)
          img
          (catch Exception e
            (log/warnf "failed creating image (%s)" (.getMessage e)))
          (finally
            (.removeImage mt img))))))
  (defn- resize [img width height]
    (if (and img
             (or (> (.getWidth img nil) width) (> (.getHeight img nil) height)))
      (let [scaled-img (.getScaledInstance img width height Image/SCALE_AREA_AVERAGING)
            mt (MediaTracker. dummy-component)]
        (try
          (.addImage mt scaled-img 1)
          (.waitForAll mt)
          scaled-img
          (catch Exception e
            (log/warnf "failed scaling image (%s)" (.getMessage e)))
          (finally
            (.removeImage mt scaled-img))))
      img)))

(defn- image-from-bytes-imageio [bs]
  (when bs
    (with-open [bais (ByteArrayInputStream. bs)]
      (ImageIO/read bais))))

(defn- image-from-url [^String url ^Image fallback-image]
  (try+
   (-> url fetch-bytes image-from-bytes-imageio)
   (catch [:status 404] {:keys [status headers body trace-redirects]}
     (log/warnf "failed fetching image (%d, %s, %s)" status headers trace-redirects)
     fallback-image)
   (catch [:status 410] {:keys [status headers body]}
     (log/warnf "failed fetching image (%d, %s)" status headers)
     fallback-image)
   (catch Exception e
     (log/warnf "failed creating image from %s (%s)" url (.getMessage e)))))

(let [CACHE-SIZE 1024
      DEFAULT-WIDTH (.width PgmPanelLayout/ICON_SIZE)
      DEFAULT-HEIGHT (.height PgmPanelLayout/ICON_SIZE)
      image-cache (proxy [LinkedHashMap] [(inc CACHE-SIZE) 1.1 true]
                                   (removeEldestEntry [entry]
                                     (if (> (proxy-super size) CACHE-SIZE)
                                       (do
                                         (log/debugf "Remove eldest image (%s)" (.. entry getKey))
                                         (.. entry getValue flush)
                                         true)
                                       false)))
      fallback-image (ImageIO/read (io/resource "noimage.png"))]
  (defn image [^String url]
    (or (.get image-cache url)
        (if-let [img (-> url
                         (image-from-url fallback-image)
                         (resize DEFAULT-WIDTH DEFAULT-HEIGHT))]
          (locking image-cache
            (.put image-cache url img)
            img)
          fallback-image))))

