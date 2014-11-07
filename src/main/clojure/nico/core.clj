;; -*- coding: utf-8-unix -*-
(ns nico.core
  (:require [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
            [decorator :as deco]
            [desktop-alert :as da]
            [nico.api :as api]
            [nico.config :as config]
            [nico.db :as db]
            [nico.net :as net]
            [nico.rss :as rss]
            [nico.status :as status]
            [nico.ui.about-dlg :as nua]
            [nico.ui.channel-panel :as nucp]
            [nico.ui.main-frame :as main-frame]
            [nico.ui.menu :as menu]
            [nico.ui.prefs-dlg :as prefs-dlg]
            [slide.core :as slc]
            [slide.logging :as sl]
            [seesaw.core :as sc]
            [seesaw.bind :as sb])
  (:import [java.awt Dimension])
  (:gen-class))

(def ^{:private true} ALERT-SIZE (Dimension. 280 70))
(def ^{:private true} ALERT-INTERVAL 100) ; ms

(defn- init-alert [alert-cfg frame]
  (let [{:keys [mode column opacity]} alert-cfg]
    (da/init-alert frame (.width ALERT-SIZE) (.height ALERT-SIZE) mode column ALERT-INTERVAL
                   (float opacity) (deco/round-rect ALERT-SIZE 8 8))))

(defn- add-channel! [ch wpanel cc]
  (letfn [(add-head! [^java.awt.Container parent ^java.awt.Component c ^long idx]
            (.add parent c idx)) ; add(Component, int)を呼び出すため
          (index-wpanel [id wpanel]
            (let [cids (->> (.getComponents wpanel)
                            (map #(-> % sc/id-of name))
                            vec)]
              [(first (keep-indexed #(when (= id %2) %1) cids))
               (dec (count cids))]))]
    (let [{:keys [id alert type email title]} ch]
      (when-let [cpanel (nucp/panel id (condp = type :comm email :kwd title))]
        (let [{:keys [c-alert btn-gear btn-menu]} (sc/group-by-id cpanel)]
          (sc/invoke-now (sc/value! c-alert alert))
          (sb/bind c-alert
                   (sb/b-do [val]
                            (ca/put! cc {:cmd :update-channel-alert :id id :alert val})))
          (sc/listen btn-gear :action (fn [_] (ca/>!! cc {:cmd :edit-channel :id id})))
          (sc/listen
           btn-menu
           :action
           (fn [_]
             (let [[idx lidx] (index-wpanel id wpanel)
                   pmenu (nucp/menu type idx lidx)
                   {:keys [move-left move-right relogin dispose]} (sc/group-by-id pmenu)]
               (sc/listen move-left :action
                          (fn [_] (when (< 0 idx) (.moveComponent wpanel idx (dec idx)))))
               (sc/listen move-right :action
                          (fn [_] (when (> lidx idx) (.moveComponent wpanel idx (inc idx)))))
               (when relogin
                 (sc/listen relogin :action
                            (fn [_] (ca/>!! cc {:cmd :relogin :id id}))))
               (sc/listen dispose :action
                          (fn [_]
                            (when (= :yes (-> (slc/confirm-dlg "Confirmation"
                                                                "Would you like to dispose the channel?")
                                              (slc/move-to-center! wpanel)
                                              sc/show!))
                              (ca/>!! cc {:cmd :dispose-channel :id id}))))
               (sc/invoke-later
                (.show pmenu cpanel (.getX btn-menu) (+ (.getY btn-menu) (.getHeight btn-menu)))))))
          (sc/invoke-now (add-head! wpanel cpanel 0)))))))

(defn- disvec [v idx]
  {:pre [(vector? v) (<= 0 idx)]}
  (vec (concat (subvec v 0 idx) (subvec v (inc idx)))))

(defn -main []
  (sc/native!)
  (sl/configure-logging-swing 200 {"handlers" "java.util.logging.ConsoleHandler"
                                   "java.util.logging.ConsoleHandler.formatter" "logutil.Log4JLikeFormatter"
                                   ".level" "INFO"
                                   "nico.level" "ALL"
                                   "slide.level" "ALL"
                                   "com.github.sgr.level" "ALL"
                                   "java.util.logging.ConsoleHandler.level" "INFO"})
  (let [cc (ca/chan)
        frame (main-frame/frame)
        menu-bar (menu/menu-bar (fn [e] (-> (nua/about-dlg)
                                            (slc/move-to-center! frame)
                                            sc/show!))
                                (fn [e] (main-frame/close! frame))
                                (fn [e] (ca/>!! cc {:cmd :edit-prefs})))
        cc-ui (status/boot frame) ;; cc-ui は以下のチャネルからの状態情報をUIに反映
        cc-db (db/boot cc-ui) ;; db -> ui
        cc-rss (rss/boot cc-ui cc-db) ;; rss -> ui, db
        cc-api (api/boot cc-ui cc-db) ;; api -> ui, db
        {:keys [wpanel spanel search-btn add-ch-btn l-search-status]} (sc/group-by-id frame)
        cfg (config/load-config)]

    (ca/>!! cc-ui {:status :set-browsers :browsers (:browsers cfg)})

    (sc/listen frame :window-closing
               (fn [e]
                 (let [src (.getSource e)
                       p (.getLocationOnScreen src)
                       win {:width (sc/width src) :height (sc/height src) :posx (.x p) :posy (.y p)}
                       cids (->> (.getComponents wpanel)
                                 (map #(-> % sc/id-of name))
                                 vec)]
                   (ca/>!! cc {:cmd :reg-window :window win})
                   (ca/>!! cc {:cmd :reg-channels :cids cids})
                   (ca/close! cc))))

    (.. frame getRootPane (setJMenuBar menu-bar))

    (let [{:keys [rss-btn api-btn view-log-item]} (sc/group-by-id frame)]
      (sc/listen view-log-item :action (fn [_] (sl/log-dlg frame "Application Log" :visible? true)))
      (sc/listen rss-btn :action (fn [_] (ca/>!! cc-rss {:cmd :act})))
      (sc/listen api-btn :action (fn [_] (ca/>!! cc-api {:cmd :start}))))

    (doseq [[id lmap] {:#add-user-channel-item {:action {:cmd :add-user-channel}}
                       :#search-btn {:action {:cmd :search-ondemand}}
                       :#add-ch-btn {:action {:cmd :add-kwd-channel-spanel}}}]
      (when-let [target (sc/select frame [id])]
        (doseq [[event cmd] lmap]
          (sc/listen target event (fn [e] (ca/>!! cc (assoc cmd :source (sc/to-widget e))))))))

    (letfn [(index-channel [id channels]
              (->> channels
                   (keep-indexed #(when (= id (:id %2)) %1))
                   first))
            (channel [id channels]
              (->> channels
                   (keep #(when (= id (:id %)) %))
                   first))]

      ;; main loop
      (ca/go-loop [cfg cfg
                   async-ops #{}]

        (let [[c op] (ca/alts! (conj (seq async-ops) cc))]
          (log/tracef "MAIN-LOOP: %s" (pr-str c))
          (if c
            (condp = (:cmd c)
              ;; 以下のコマンド群はcfgを変更するためその場でrecurする。
              :update-config (let [new-cfg (:config c)
                                   old-alert-cfg (:alert cfg)
                                   new-alert-cfg (:alert new-cfg)
                                   old-browsers-cfg (:browsers cfg)
                                   new-browsers-cfg (:browsers new-cfg)]
                               (when (not= old-alert-cfg new-alert-cfg)
                                 (init-alert new-alert-cfg frame))
                               (when (not= old-browsers-cfg new-browsers-cfg)
                                 (ca/>! cc-ui {:status :set-browsers :browsers new-browsers-cfg}))
                               (recur (merge cfg new-cfg) (disj async-ops op)))
              :add-channel (let [ch (:channel c)
                                 id (:id ch)]
                             (add-channel! ch wpanel cc)
                             (condp = (:type ch)
                               :kwd (let [{:keys [title query target]} ch]
                                      (ca/>! cc-db {:cmd :set-query-kwd :id id :query query :target target})
                                      (ca/>! cc-ui {:status :set-channel-title :id id :title title}))
                               :comm (let [{:keys [id email passwd]} ch]
                                       (ca/>! cc-api {:cmd :login :id id :email email :passwd passwd})))
                             (ca/>! cc-ui {:status :set-channel-alert :id id :alert (:alert ch)})
                             (recur (update-in cfg [:channels] conj ch) (disj async-ops op)))
              :dispose-channel (let [id (:id c)]
                                 (when-let [idx (index-channel id (:channels cfg))]
                                   (let [ch (nth (:channels cfg) idx)]
                                     (condp = (:type ch)
                                       :kwd (ca/>! cc-db {:cmd :rem-query :id id})
                                       :comm (ca/>! cc-api {:cmd :rem-alert-status :id id}))
                                     (ca/>! cc-ui {:status :dispose-channel :id id})
                                     (recur (update-in cfg [:channels] disvec idx) async-ops))))
              :update-channel-alert (let [{:keys [id alert]} c
                                          idx (index-channel id (:channels cfg))]
                                      (when-let [ch (channel id (:channels cfg))]
                                        (ca/>! cc-ui {:status :set-channel-alert :id id :alert alert})
                                        (recur (update-in cfg [:channels] assoc idx (assoc ch :alert alert))
                                               async-ops)))
              :update-channel (let [ch (:channel c)
                                    id (:id ch)]
                                (when-let [idx (index-channel id (:channels cfg))]
                                  (condp = (:type ch)
                                    :kwd (let [{:keys [title query target]} ch]
                                           (ca/>! cc-db {:cmd :set-query-kwd :id id :query query :target target})
                                           (ca/>! cc-ui {:status :set-channel-title :id id :title title}))
                                    :comm (let [{:keys [id email passwd]} ch]
                                            (ca/>! cc-api {:cmd :login :id id :email email :passwd passwd})))
                                  (recur (update-in cfg [:channels] assoc idx ch) (disj async-ops op))))

              ;; 以下のコマンド群は現在のcfgを入力とし、他のチャネルにコマンドを発行する。
              ;; cfgには変更なし。
              :init-channels (let [channels (:channels cfg)]
                               (doseq [ch (reverse channels)]
                                 (add-channel! ch wpanel cc))
                               (doseq [ch channels]
                                 (condp = (:type ch)
                                   :kwd (let [{:keys [id title query target]} ch]
                                          (ca/>! cc-db {:cmd :set-query-kwd :id id :query query :target target})
                                          (ca/>! cc-ui {:status :set-channel-title :id id :title title}))
                                   :comm (let [{:keys [id email passwd]} ch]
                                           (ca/>! cc-api {:cmd :login :id id :email email :passwd passwd})))
                                 (ca/>! cc-ui {:status :set-channel-alert :id (:id ch) :alert (:alert ch)}))
                               (recur cfg async-ops))
              :search-ondemand (let [spec (deref (sc/user-data spanel))]
                                 (sc/invoke-now
                                  (sc/config! search-btn :enabled? false)
                                  (sc/config! add-ch-btn :enabled? false)
                                  (sc/value! l-search-status "Searching..."))
                                 (ca/>! cc-db (assoc spec :cmd :search-ondemand))
                                 (recur cfg async-ops))
              :relogin (let [id (:id c)]
                         (when-let [ch (channel id (:channels cfg))]
                           (let [{:keys [id email passwd]} ch]
                             (ca/>! cc-api {:cmd :login :id id :email email :passwd passwd})))
                         (recur cfg async-ops))

              ;; 以下のコマンド群は現在のcfgを入力とするが結果は人間の操作により非同期に得られ、
              ;; cfgに変更があれば更新系コマンドでccに返す。
              :edit-prefs (let [op (ca/go
                                     (when-let [new-prefs (sc/invoke-now
                                                           (-> (prefs-dlg/dlg cfg)
                                                               (slc/move-to-center! frame)
                                                               sc/show!))]
                                       {:cmd :update-config :config new-prefs}))]
                            (recur cfg (conj async-ops op)))
              :add-user-channel (let [op (ca/go
                                           (when-let [new-ch (sc/invoke-now
                                                              (-> (nucp/dlg-comm (config/init-user-channel))
                                                                  (slc/move-to-center! frame)
                                                                  sc/show!))]
                                             {:cmd :add-channel :channel new-ch}))]
                                  (recur cfg (conj async-ops op)))
              :add-kwd-channel-spanel (let [spec (deref (sc/user-data spanel))
                                            ch (merge (config/init-kwd-channel) spec)
                                            btn (sc/select spanel [:#add-ch-btn])
                                            op (ca/go
                                                 (try
                                                   (when-let [new-ch (sc/invoke-now
                                                                      (-> (nucp/dlg-kwd-title ch)
                                                                          (slc/move-to-center! frame)
                                                                          sc/show!))]
                                                     {:cmd :add-channel :channel new-ch})
                                                   (finally
                                                     (sc/invoke-later (sc/config! btn :enabled? true)))))]
                                        (sc/invoke-later (sc/config! btn :enabled? false))
                                        (recur cfg (conj async-ops op)))
              :edit-channel (let [ch (channel (:id c) (:channels cfg))]
                              (when-let [dlg (condp = (:type ch)
                                               :comm (nucp/dlg-comm ch)
                                               :kwd (nucp/dlg-kwd ch)
                                               nil)]
                                (let [op (ca/go
                                           (when-let [new-ch (sc/invoke-now
                                                              (-> dlg
                                                                  (slc/move-to-center! frame)
                                                                  sc/show!))]
                                             {:cmd :update-channel :channel new-ch}))]
                                  (recur cfg (conj async-ops op)))))

              ;; アプリ終了直前に呼び出されるコマンド
              :reg-window (recur (assoc cfg :window (:window c)) async-ops)
              :reg-channels (let [chm (->> (:channels cfg)
                                           (map (fn [ch] [(:id ch) ch]))
                                           flatten
                                           (apply hash-map))
                                  chs (vec (map #(get chm %) (:cids c)))]
                              (log/infof "register channels: %s"
                                         (pr-str (map #((condp = (:type %) :comm :email :kwd :title) %) chs)))
                              (recur (assoc cfg :channels chs) async-ops))

              ;; 未対応コマンドを受けた場合はログに残す。
              (do
                (log/warnf "Caught an unknown command: %s" (pr-str c))
                (recur cfg async-ops)))

            (if (not= op cc)
              (do
                (log/infof "Caught nil from async-ops (maybe cancel op): %s" (pr-str op))
                (ca/close! op)
                (recur cfg (disj async-ops op)))
              (do ;; ccが閉じた→アプリの終了
                (log/info "Main control channel has been closed. Closing other control channels now...")
                (ca/close! cc-rss)
                (ca/close! cc-api)
                (ca/close! cc-db)
                (ca/close! cc-ui)
                (when (pos? (count async-ops))
                  (log/warnf "Unfinished operations: %s" (pr-str async-ops)))
                (da/close-alert)
                (log/info "Store the config now...")
                (config/store-config cfg)
                (net/shutdown-conn-manager)
                (System/exit 0)))))))

    ;; アラーターの初期化
    (init-alert (:alert cfg) frame)

    ;; initialize channels
    (ca/>!! cc {:cmd :init-channels})

    (let [w (get-in cfg [:window :width])
          h (get-in cfg [:window :height])
          x (get-in cfg [:window :posx])
          y (get-in cfg [:window :posy])]
      (-> frame
          sc/pack!
          (sc/config! :size [w :by h])
          (sc/move! :to [x y])
          sc/show!))))
