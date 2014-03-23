;; -*- coding: utf-8-unix -*-
(ns nico.core-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [clojure.core.async :as ca]))

(def ALLOC-SZ 10240000)

(defn- tick-tack [cmd times wait]
  (when (= 0 (mod times 1000))
    (log/infof "times [%s]: %d" (name cmd) times))
  (when (= :tack cmd)
    (let [ary (byte-array ALLOC-SZ)]
      (dotimes [n ALLOC-SZ]
        (aset-byte ary n (mod n 127)))))
  (if (= 0 times)
    (log/info "finished tick-tack")
    (ca/go
      (ca/<! (ca/timeout wait))
      {:cmd (if (= :tick cmd) :tack :tick) :wait wait :times (dec times)})))

(deftest ^{:stress true :core true} core-async-test
  (let [cc (ca/chan)
        lc (ca/go-loop [curr-op nil]
             (let [[c ch] (ca/alts! (if curr-op [curr-op cc] [cc]))]
               (if c
                 (condp = (:cmd c)
                   :tick (let [{:keys [cmd times wait]} c]
                           (when-let [op (tick-tack cmd times wait)]
                             (recur op)))
                   :tack (let [{:keys [cmd times wait]} c]
                           (when-let [op (tick-tack cmd times wait)]
                             (recur op)))
                   (log/error "Unknown command: " (pr-str c)))
                 (cond
                  (not= ch cc) (log/error "Closed other channel: " (pr-str ch))
                  :else (log/info "Closed control channel")))))]
    (ca/>!! cc {:cmd :tick :times 1000000 :wait 5})
    (ca/<!! lc)))

