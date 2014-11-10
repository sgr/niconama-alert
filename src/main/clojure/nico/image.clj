;; -*- coding: utf-8-unix -*-
(ns nico.image
  (:require [clojure.tools.logging :as log]
            [nico.net :as net])
  (:use [slingshot.slingshot :only [try+]])
  (:import [java.awt Image Component MediaTracker Toolkit]
           [java.io InputStream ByteArrayInputStream]
           [java.util LinkedHashMap]
           [javax.imageio ImageIO]
           [nico.ui PgmPanelLayout]))

(let [tk (Toolkit/getDefaultToolkit)
      dummy-component (proxy [Component][])]
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
    (if (and img (instance? Image img)
             (or (> (.getWidth img nil) width) (> (.getHeight img nil) height)))
      (let [scaled-img (.getScaledInstance img width height Image/SCALE_AREA_AVERAGING)
            mt (MediaTracker. dummy-component)]
        (try
          (.addImage mt scaled-img 1)
          (.waitForAll mt)
          (.flush img)
          scaled-img
          (catch Exception e
            (log/warnf "failed scaling image (%s)" (.getMessage e))
            img)
          (finally
            (.removeImage mt scaled-img))))
      img)))

(defn- image-from-bytes-imageio [bs]
  (when bs
    (with-open [bais (ByteArrayInputStream. bs)]
      (ImageIO/read bais))))

(defn- image-from-url-aux
  "URLの指すイメージを返す。イメージが存在しない場合はfallback-imageを返し、それ以外のエラーの場合はnilを返す。"
  [^String url ^Image fallback-image read-fn]
  (try+
   (-> url (net/http-get {:as :byte-array}) :body read-fn)
   (catch [:status 404] {:keys [status headers body trace-redirects]}
     (log/warnf "failed fetching image (%d, %s, %s)" status headers trace-redirects)
     (read-fn body))
   (catch [:status 410] {:keys [status headers body]}
     (log/warnf "failed fetching image (%d, %s)" status headers)
     fallback-image)
   (catch Object _
     (log/warnf "failed fetching image (%s, %s)" url (:message &throw-context)))))

(defn- image-from-url
  "URLの指すイメージを返す。イメージが存在しない場合はfallback-imageを返し、それ以外のエラーの場合はnilを返す。"
  [^String url ^Image fallback-image read-fn]
  (let [FETCH-INTERVAL-MSEC 1000
        FETCH-LIMIT 10]
    (loop [retry 1]
      (if (= FETCH-LIMIT retry)
        fallback-image
        (or (image-from-url-aux url fallback-image read-fn)
            (do
              (log/warnf "retry (%d) fetching image (%s)" retry url)
              (Thread/sleep (* retry FETCH-INTERVAL-MSEC))
              (recur (inc retry))))))))

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
                        false)))]
  (defn image [^String url]
    (or (locking image-cache (.get image-cache url))
        (when-let [img (-> url
                           (image-from-url nil image-from-bytes-imageio)
                           (resize DEFAULT-WIDTH DEFAULT-HEIGHT))]
          (locking image-cache (.put image-cache url img))
          img))))
