;; -*- coding: utf-8-unix -*-
(ns nico.net
  (:require [clojure.tools.logging :as log]
            [clj-http.client :as hc]
            [clj-http.conn-mgr :as cm])
  (:import [java.util.concurrent Callable Executors Future TimeUnit]))

(let [nthreads 3
      cm (cm/make-reusable-conn-manager {:timeout 10 :threads nthreads :default-per-route nthreads})
      pool (Executors/newFixedThreadPool nthreads)
      HTTP-OPTS  {:headers {"user-agent" "NiconamaAlert.clj"}
                  :connection-manager cm
                  :conn-timeout   2000
                  :socket-timeout 5000
                  :force-redirects true
                  :follow-redirects true}]
  (defn http-get [url & opts]
    (let [^Callable c (cast Callable #(hc/get url (merge HTTP-OPTS (first opts))))
          ^Future task (.submit pool c)]
      (.get task)))
  (defn http-post [url & opts]
    (let [^Callable c (cast Callable #(hc/post url (merge HTTP-OPTS (first opts))))
          ^Future task (.submit pool c)]
      (.get task)))
  (defn shutdown-conn-manager []
    (log/info "shutdown HTTP connection manager")
    (.shutdown pool)
    (cm/shutdown-manager cm)
    (.awaitTermination pool 5 TimeUnit/SECONDS)))
