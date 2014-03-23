;; -*- coding: utf-8-unix -*-
(ns nico.net
  (:require [clojure.tools.logging :as log]
            [clj-http.client :as hc]
            [clj-http.conn-mgr :as hcm]))

(def ^{:private true} CONNECT-TIMEOUT 5000)

(def ^{:private true} HTTP-OPTS
  {:headers {"user-agent" "NiconamaAlert.clj"}
   :socket-timeout CONNECT-TIMEOUT
   :conn-timeout   CONNECT-TIMEOUT})

(let [default-opts HTTP-OPTS]
  (defn http-get [url & opts]
    (hc/get url (if opts (reduce merge default-opts opts) default-opts)))
  (defn http-post [url & opts]
    (hc/post url (if opts (reduce merge default-opts opts) default-opts))))

(defmacro with-http-res [bindings & body]
  (assert (vector? bindings)     "with-http-res: a vector for its binding")
  (assert (= 2 (count bindings)) "with-http-res: two number of forms in binding vector")
  `(let ~bindings
     (let [raw-res# ~(first bindings) status# (:status raw-res#) body# (:body raw-res#)]
       (if (= 200 status#)
         (do ~@body)
         (let [msg# (format "returned HTTP error: %d, %s" status# body#)]
           (log/error msg#)
           (throw (Exception. msg#)))))))

