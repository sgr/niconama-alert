;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "本アプリケーションの設定"}
  nico.prefs
  (:use [clojure.tools.logging])
  (:require [io-utils :as io]
            [prefs-utils :as pu]
	    [str-utils :as s])
  (:import [java.awt Desktop GraphicsEnvironment]
           [java.io File IOException]
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

(defn ^File pref-dir []
  (File. (pu/pref-base-path)
         (condp = (pu/system)
           :windows "NiconamaAlertCLJ"
           :mac     "NiconamaAlertCLJ"
           :linux   ".NiconamaAlertCLJ"
           :bsd     ".NiconamaAlertCLJ"
           :solaris ".NiconamaAlertCLJ"
           ".NiconamaAlertCLJ")))

(defn check-pref-dir []
  (let [p (pref-dir)]
    (when-not (.exists p)
      (when-not (.mkdirs p)
        (throw (IOException. (format "failed creating pref-dir: %s" (.getCanonicalPath p))))))))

(defn- ^File old-pref-dir []
  (File. (pu/pref-base-path)
         (condp = (pu/system)
           :mac     "Application Support"
           "")))

(defn- ^File old-pref-file [] (File. (old-pref-dir) ".nico.clj"))
(defn- ^File pref-file [] (File. (pref-dir) "config.clj"))

(let [p (atom nil)]
  (defn get-pref [] p)
  (defn load-pref []
    (when-not @p
      (reset! p
              (if-let [lp (pu/load-pref (pref-file))]
                lp
                (let [opf (old-pref-file)]
                  (if (.exists opf)
                    (do (info (format "loading old pref: %s" (.getCanonicalPath opf)))
                        (if-let [op (pu/load-pref opf)]
                          op
                          (gen-initial-pref)))
                    (gen-initial-pref))))))
    @p)
  (defn store-pref [] (pu/store-pref @p (pref-file))))

(defn db-path []
  (condp = (pu/system)
    :windows (io/temp-file-name "nico-" ".db")
    :mac     (.getCanonicalPath (File. (pref-dir) "pgm.db"))
    :linux   (io/temp-file-name "nico-" ".db")
    :bsd     (io/temp-file-name "nico-" ".db")
    :solaris (io/temp-file-name "nico-" ".db")
    (io/temp-file-name "nico-" ".db")))

(defn cache-dir [] (File. (pref-dir) "thumbnail"))

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

