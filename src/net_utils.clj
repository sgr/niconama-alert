;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Utility for networking."}
  net-utils)

(def SOCKET-TIMEOUT  5000)
(def CONNECT-TIMEOUT 5000)
(def READ-TIMEOUT   10000)

(defn url-stream
  ([^String url ^Integer connect-timeout ^Integer read-timeout]
     (let [u (java.net.URL. url)]
       (.getInputStream (doto (.openConnection u)
			  (.setConnectTimeout connect-timeout)
			  (.setReadTimeout read-timeout)))))
  ([^String url] (url-stream url CONNECT-TIMEOUT READ-TIMEOUT)))
