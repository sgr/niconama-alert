;; -*- coding: utf-8-unix -*-
(ns nico.scrape-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [net.cgrand.enlive-html :as html]
            [nico.scrape :refer :all])
  (:import [java.io StringReader]))

(defn- pgm-test [f-url]
  (let [clean @#'nico.scrape/clean
        serialize @#'nico.scrape/serialize]
    (with-open [is (io/input-stream f-url)
                rdr (-> is clean serialize (StringReader.))]
      (let [xml (html/xml-resource rdr)
            pgm (extract-pgm xml)]
        (log/infof "PGM: %s" (pr-str pgm))))))

(deftest ^{:scrape true :data true} extract-pgm-test
  (let [comm-file (io/resource "sample-pgm-comm")
        chan-file (io/resource "sample-pgm-channel")
        ;;offi-file (io/resource "sample-pgm-official")
        comm-file-old (io/resource "sample-pgm-comm-old")
        chan-file-old (io/resource "sample-pgm-channel-old")
        ;;offi-file-old (io/resource "sample-pgm-official-old")
        ]
    (pgm-test comm-file)
    (pgm-test chan-file)
    (pgm-test comm-file-old)
    (pgm-test chan-file-old)
    ;;(pgm-test offi-file)
    ))

(deftest ^{:net true :data true} scrape-pgm-test
  (let [fetch-pgm @#'nico.scrape/fetch-pgm]
    (let [ids (read-string (slurp (io/resource "sample-scrape-id")))
          pgm (apply fetch-pgm ids)]
      (log/infof "PGM[%s,%s]: %s" (first ids) (second ids) (pr-str pgm)))))
