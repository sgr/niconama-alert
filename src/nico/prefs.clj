;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "本アプリケーションの設定"}
  nico.prefs
  (:require [prefs-utils :as pu])
  (:import (java.awt GraphicsEnvironment)))

(def *default-frame-width* 900)
(def *default-frame-height* 500)

(defn- gen-initial-pref []
  (let [e (GraphicsEnvironment/getLocalGraphicsEnvironment)
	p (.getCenterPoint e)]
    {:window {:width *default-frame-width* :height *default-frame-height*
	      :posx (- (.x p) (int (/ *default-frame-width* 2))) :posy (- (.y p) (int (/ *default-frame-height* 2)))}
     :tabs [] :browsers [[:default :default true]]}))

(defn gen-initial-user-tpref []
  {:type :comm :email nil :passwd nil})

(defn gen-initial-keyword-tpref []
  {:type :kwd :title nil :query nil :category '()})

(let [p (atom {})]
  (defn get-pref [] p)
  (defn load-pref [] (reset! p (if-let [lp (pu/load-pref "nico")] lp (gen-initial-pref))) p)
  (defn store-pref [] (pu/store-pref @p "nico")))

