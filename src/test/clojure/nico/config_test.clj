;; -*- coding: utf-8-unix -*-
(ns nico.config-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [config-file :as cf]
            [nico.config :refer :all]))

(deftest version-test
  (testing "default-browser-config"
    (is (= "1.7.0" (app-version-str)))
    (is (= "1.7.0" (config-version-str)))))

(deftest check-config-test
  (let [init-config @#'nico.config/init-config
        check-old-config @#'nico.config/check-old-config]
    (testing "don't change (init-config)"
      (is (= (init-config) (check-old-config (init-config)))))
    (testing "don't change translated old config data"
      (when-let [old-cfg (-> (io/resource "sample-old-config.clj")
                             (io/file)
                             (cf/load-config))]
        (let [translated-cfg (check-old-config old-cfg)]
          (is (= translated-cfg (check-old-config translated-cfg))))))
    ))
