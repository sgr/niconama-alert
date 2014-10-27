;; -*- coding: utf-8-unix -*-
(ns nico.net
  (:require [clojure.tools.logging :as log]
            [clj-http.client :as hc]
            [clj-http.conn-mgr :as cm]))

(let [cm (cm/make-reusable-conn-manager {:timeout 90 :threads 6})
      HTTP-OPTS  {:headers {"user-agent" "NiconamaAlert.clj"}
                  :connection-manager cm
                  :conn-timeout   5000
                  :socket-timeout 90000
                  :follow-redirects true}]
  (defn http-get [url & opts] (hc/get url (merge HTTP-OPTS (first opts))))
  (defn http-post [url & opts] (hc/post url (merge HTTP-OPTS (first opts))))
  (defn shutdown-conn-manager [] (cm/shutdown-manager cm)))

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

