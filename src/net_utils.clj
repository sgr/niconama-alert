;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Utility for networking."}
  net-utils
  (:use [clojure.tools.logging]))

(def ^{:private true} CONNECT-TIMEOUT 5000)
(def ^{:private true} READ-TIMEOUT    5000)

(def HTTP-OPTS
  {:headers {"user-agent" "Niconama-alert J/1.1.0"}
   :socket-timeout CONNECT-TIMEOUT
   :conn-timeout   CONNECT-TIMEOUT})

(defmacro with-http-res [bindings & body]
  (assert (vector? bindings)     "with-http-res: a vector for its binding")
  (assert (= 2 (count bindings)) "with-http-res: two number of forms in binding vector")
  `(let ~bindings
     (let [raw-res# ~(first bindings) status# (:status raw-res#) body# (:body raw-res#)]
       (if (= 200 status#)
         (do ~@body)
         (let [msg# (format "returned HTTP error: %d, %s" status# body#)]
           (error msg#)
           (throw (Exception. msg#)))))))

(defn url-stream
  ([^String url ^Integer connect-timeout ^Integer read-timeout]
     (let [u (java.net.URL. url)]
       (.getInputStream (doto (.openConnection u)
			  (.setConnectTimeout connect-timeout)
			  (.setReadTimeout read-timeout)))))
  ([^String url] (url-stream url CONNECT-TIMEOUT READ-TIMEOUT)))
