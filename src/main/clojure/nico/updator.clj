;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコ生の番組情報を更新する。"}
    nico.updator
  (:require [nico.rss-updator :as rss]
	    [nico.api-updator :as api]
	    [nico.pgm :as pgm]))

;; 各更新スレッドを起動
(let [rss-updator (atom {:updator (Thread. ^Runnable rss/update-rss)
                         :started false})
      api-updator (atom {:updator (Thread. ^Runnable api/update-api)
                         :started false})
      rate-updator (atom {:updator (Thread. ^Runnable api/update-rate)
        		  :started false})]
  (defn start-updators []
    (doseq [u [rss-updator api-updator rate-updator]]
      (when-let [^Thread t (:updator (deref u))]
        (when-not (:started (deref u))
          (.start t)
          (reset! u (assoc (deref u) :started true)))))))

