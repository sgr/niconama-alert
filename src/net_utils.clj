;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Utility for networking."}
  net-utils)

(def *connect-timeout* 5000)
(def *read-timeout* 5000)

(defn url-stream
  ([^String url ^int connect-timeout ^int read-timeout]
     (let [u (java.net.URL. url)]
       (.getInputStream (doto (.openConnection u)
			  (.setConnectTimeout connect-timeout)
			  (.setReadTimeout read-timeout)))))
  ([^String url] (url-stream url *connect-timeout* *read-timeout*)))
