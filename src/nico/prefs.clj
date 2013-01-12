;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "本アプリケーションの設定"}
  nico.prefs
  (:use [clojure.tools.logging])
  (:require [prefs-utils :as pu]
	    [str-utils :as s])
  (:import [java.awt Desktop GraphicsEnvironment]
	   [java.net URI URL]))

(def ^{:private true} DEFAULT-FRAME-WIDTH 895)
(def ^{:private true} DEFAULT-FRAME-HEIGHT 560)

(defn- gen-initial-pref []
  (let [e (GraphicsEnvironment/getLocalGraphicsEnvironment)
	p (.getCenterPoint e)]
    {:window {:width DEFAULT-FRAME-WIDTH :height DEFAULT-FRAME-HEIGHT
	      :posx (- (.x p) (int (/ DEFAULT-FRAME-WIDTH 2)))
	      :posy (- (.y p) (int (/ DEFAULT-FRAME-HEIGHT 2)))}
     :tabs [{:type :all :title "All" :alert false}]
     :browsers [[:default :default true]]}))

(let [p (atom {})]
  (defn get-pref [] p)
  (defn load-pref [] (reset! p (if-let [lp (pu/load-pref "nico")] lp (gen-initial-pref))) @p)
  (defn store-pref [] (pu/store-pref @p "nico")))

(defn gen-initial-user-tpref []
  {:type :comm :email nil :passwd nil :alert true})

(defn gen-initial-keyword-tpref []
  {:type :kwd :title nil :query nil :category '() :alert true})

(defn- open-url-aux [cmd url]
  (if (= :default cmd)
    (do
      (debug (format "open by default browser: %s" url))
      (.browse (Desktop/getDesktop) (URI. url)))
    (do
      (let [cmds (s/tokenize cmd)]
	(debug (format "open by %s: %s" (pr-str cmds) url))
	(.start (ProcessBuilder. ^java.util.List (conj cmds url)))))))

(defn open-url
  "kindで指定されたブラウザでurlを開く。kindに指定できるのは、:firstと:alert。"
  [kind url]
  (let [[name cmd] (condp = kind
		       :first (first (:browsers @(get-pref)))
		       :alert (some #(let [[name cmd alert] %] (if alert % false))
				    (:browsers @(get-pref))))]
    (open-url-aux cmd url)))

(defn browsers []
  (map #(let [[name cmd] %] [name (fn [url] (open-url-aux cmd url))])
       (:browsers @(get-pref))))
