;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Utility for networking."}
  net-utils
  (:use [clojure.tools.logging])
  (:import [java.util.concurrent TimeUnit]
           [java.net SocketTimeoutException]))

(def ^{:private true} CONNECT-TIMEOUT 5000)
(def ^{:private true} READ-TIMEOUT    5000)
(def ^{:private true} INTERVAL-RETRY  2000)

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

(defn url-stream [^String url]
  (letfn [(url-stream-aux [^String url ^Integer connect-timeout ^Integer read-timeout]
            (try
              (let [u (java.net.URL. url)]
                (.getInputStream (doto (.openConnection u)
                                   (.setConnectTimeout connect-timeout)
                                   (.setReadTimeout read-timeout))))
              (catch SocketTimeoutException e
                (warn e (format "timeouted open input stream from: %s (%d, %d)" url connect-timeout read-timeout))
                nil)))]
    (if-let [is (url-stream-aux url CONNECT-TIMEOUT READ-TIMEOUT)]
      is
      (do (.sleep TimeUnit/SECONDS INTERVAL-RETRY)
          (recur url)))))
