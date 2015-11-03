;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "本アプリケーションの設定"}
  nico.config
  (:require [clojure.string :as s]
            [clojure.tools.logging :as log]
            [config-file :as cf]
	    [input-parser.tokenizer :as tok])
  (:import [java.awt Desktop GraphicsEnvironment]
           [java.io File IOException]
	   [java.net URI URL]
           [java.util UUID]))

(def APP-TITLE "niconama-alert.clj")
(def APP-VERSION [1 7 6])
(def CONFIG-VERSION [1 7 0])

(def ^{:private true} DEFAULT-FRAME-WIDTH 895)
(def ^{:private true} DEFAULT-FRAME-HEIGHT 560)

(defn default-alert-config []
  {:mode (if (= :windows (cf/system)) :rl-bt :rl-tb)
   :column 1 :opacity 0.9})

(def DEFAULT-BROWSER-CONFIG [:default :default])
(defn default-browsers-config [] [DEFAULT-BROWSER-CONFIG]) ; 1.6迄は [[:default :default true]] だった。

(defn ^File pref-dir []
  (File. (cf/config-base-path)
         (condp = (cf/system)
           :windows "NiconamaAlertCLJ"
           :mac     "NiconamaAlertCLJ"
           :linux   ".NiconamaAlertCLJ"
           :bsd     ".NiconamaAlertCLJ"
           :solaris ".NiconamaAlertCLJ"
           ".NiconamaAlertCLJ")))

(defn- ^File pref-file [] (File. (pref-dir) "config.clj"))

(defn- init-config []
  (let [e (GraphicsEnvironment/getLocalGraphicsEnvironment)
	p (.getCenterPoint e)]
    {:window {:width DEFAULT-FRAME-WIDTH :height DEFAULT-FRAME-HEIGHT
	      :posx (- (.x p) (int (/ DEFAULT-FRAME-WIDTH 2)))
	      :posy (- (.y p) (int (/ DEFAULT-FRAME-HEIGHT 2)))}
     :version CONFIG-VERSION
     :alert (default-alert-config) ; 1.6から新しく設定可能になった。
     :browsers (default-browsers-config)
     :channels []}))

(defn- uuid-str [] (.toString (UUID/randomUUID)))

(defn- check-old-channel [ch]
  (letfn [(check-id [ch] ; 1.6までは:idが存在しない
            (assoc ch :id (or (:id ch) (uuid-str))))
          (check-target [ch] ; 1.6までは:targetはリストだった。また:descriptionは:descだった
            (if (:target ch) (assoc ch :target
                                    (->> (:target ch)
                                         (map #(if (= :desc %) :description %))
                                         set))
                ch))]
    (-> ch
        check-id
        check-target)))

(defn- check-old-browser [b]
  (if (= 3 (count b)) ; 1.6までは3つ目の要素があった
    [(first b) (second b)]
    b))

(defn- check-old-config
  "1.6までの設定ファイルを最新の設定に更新"
  [cfg]
  (letfn [(check-old-version [cfg] ; 1.6までは存在しない。今後のバージョンアップで用いる予定。
            (assoc cfg :version (or (:version cfg) CONFIG-VERSION)))
          (check-old-alert [cfg] ; 1.6までは存在しない
            (assoc cfg :alert (or (:alert cfg) (default-alert-config))))
          (check-old-channels [cfg]
            (let [tabs (:tabs cfg)
                  ncfg (dissoc cfg :tabs)
                  chs (:channels cfg)]
              (assoc ncfg :channels (or chs (vec (map check-old-channel tabs))))))
          (check-old-browsers [cfg]
            (assoc cfg :browsers (vec (map check-old-browser (:browsers cfg)))))]
    (-> cfg
        check-old-version
        check-old-alert
        check-old-channels
        check-old-browsers)))

(defn init-user-channel []
  {:id (uuid-str) :type :comm :email nil :passwd nil :alert false})

(defn init-kwd-channel []
  {:id (uuid-str) :type :kwd :title nil :target #{} :alert false})

(defn load-config []
  (if-let [cfg (cf/load-config (pref-file))]
    (check-old-config cfg)
    (init-config)))

(defn store-config [cfg]
  (let [p (pref-dir)]
    (when-not (.exists p)
      (when-not (.mkdirs p)
        (throw (IOException. (format "failed creating pref-dir: %s" (.getCanonicalPath p)))))))
  (cf/store-config cfg (pref-file)))

(defn app-version-str [] (s/join "." APP-VERSION))
(defn config-version-str [] (s/join "." CONFIG-VERSION))
