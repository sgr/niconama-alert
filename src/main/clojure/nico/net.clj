;; -*- coding: utf-8-unix -*-
(ns nico.net
  (:require [clojure.tools.logging :as log]
            [clj-http.client :as hc]
            [clj-http.conn-mgr :as cm]
            [clojure.core.async :as ca])
  (:import  [java.util.concurrent TimeUnit]
            [org.apache.http.pool PoolStats]
            [org.apache.http.impl.conn PoolingClientConnectionManager]))

(let [nthreads 3
      ^PoolingClientConnectionManager cm (cm/make-reusable-conn-manager {:timeout 10 :threads nthreads :default-per-route nthreads})
      cc (ca/chan)
      HTTP-OPTS  {:headers {"user-agent" "NiconamaAlert.clj"}
                  :connection-manager cm
                  :conn-timeout   2000
                  :socket-timeout 5000
                  :force-redirects true
                  :follow-redirects true}]
  (ca/go-loop []
    (let [[c ch] (ca/alts! [cc (ca/timeout 5000)])]
      (when-not (= cc ch)
        (let [^PoolStats ps (.getTotalStats cm)]
          (when (.getPending ps)
            (log/debugf "POOL STATS %s" (.toString ps))))
        (.closeExpiredConnections cm)
        (.closeIdleConnections cm 10 TimeUnit/SECONDS)
        (recur))))
  (defn http-get [url & opts]
    (hc/get url (merge HTTP-OPTS (first opts))))
  (defn http-post [url & opts]
    (hc/post url (merge HTTP-OPTS (first opts))))
  (defn shutdown-conn-manager []
    (log/info "shutdown HTTP connection manager")
    (ca/>!! cc :shutdown)
    (cm/shutdown-manager cm)))
