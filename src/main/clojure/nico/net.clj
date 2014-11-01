;; -*- coding: utf-8-unix -*-
(ns nico.net
  (:require [clojure.tools.logging :as log]
            [clj-http.client :as hc]
            [clj-http.conn-mgr :as cm]))

(let [nthreads 3
      cm (cm/make-reusable-conn-manager {:timeout 10 :threads nthreads :default-per-route nthreads})
      HTTP-OPTS  {:headers {"user-agent" "NiconamaAlert.clj"}
                  :connection-manager cm
                  :conn-timeout   2000
                  :socket-timeout 5000
                  :force-redirects true
                  :follow-redirects true}]
  (defn http-get [url & opts]
    (hc/get url (merge HTTP-OPTS (first opts))))
  (defn http-post [url & opts]
    (hc/post url (merge HTTP-OPTS (first opts))))
  (defn shutdown-conn-manager []
    (log/info "shutdown HTTP connection manager")
    (cm/shutdown-manager cm)))
