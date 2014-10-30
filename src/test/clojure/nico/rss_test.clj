;; -*- coding: utf-8-unix -*-
(ns nico.rss-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.xml :as xml]
            [clojure.tools.logging :as log]
            [nico.string :as s]
            [nico.rss :refer :all]))

(defn rsss [n]
  (->> (range 1 (inc n))
       (map #(io/resource (format "rss?p=%d" %)))))

(defn rss-xml [url]
  (when (.exists (io/file url))
    (-> (slurp url) s/utf8stream xml/parse)))

(defn extract-pgms [rx & {:keys [type] :or {type :user} :as opts}]
  (extract rx ({:official create-official-pgm
                :user create-pgm} type)))

(defn- pgm-test [rx & {:keys [type print? trace?] :or {type :user print? true trace? false} :as opts}]
  (let [pgms (extract-pgms rx :type type)]
    (when print?
      (log/infof "EXTRACTED PGMS[%d]" (count pgms)))
    (when trace?
      (doall (map-indexed (fn [i pgm] (log/infof " PGMS[%d]: %s" i (pr-str pgm))) pgms)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ^{:rss true :data true} parse-official-rss-test
  (let [rx (rss-xml (io/resource "rss"))]
    (pgm-test rx :type :official :trace? true)))

(deftest ^{:rss true :data true} parse-user-rss-test
  (doseq [rx (map rss-xml (rsss 180))]
    (pgm-test rx :type :user :trace? true)))

(deftest ^{:stress true :rss true :data true} rss-stress-tes
  (let [orx (rss-xml (io/resource "rss"))
        rxs (map rss-xml (rsss 180))]
    (dotimes [n 400000]
      (when (= 0 (mod n 1000)) (log/infof "parsing RSS [%d]" n))
      (pgm-test orx :type :official :print? false)
      (doseq [rx rxs]
        (pgm-test rx :type :user :print? false)))))
