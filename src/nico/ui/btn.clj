;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "dialog button."}
  nico.ui.btn
  (:import (java.awt Dimension)
	   (javax.swing JButton)))

(def *btn-size* (Dimension. 100 20))

(defn btn [text]
  (let [b (JButton.)]
    (doto b
      (.setText text)
      (.setMaximumSize *btn-size*)
      (.setMinimumSize *btn-size*)
      (.setPreferredSize *btn-size*))))
