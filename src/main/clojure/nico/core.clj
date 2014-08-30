;; -*- coding: utf-8-unix -*-
(ns nico.core
  (:require [clojure.core.async :as ca]
            [clojure.java.browse :as browse]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [decorator :as deco]
            [desktop-alert :as da]
            [input-parser.tokenizer :as tok]
            [nico.api :as api]
            [nico.config :as config]
            [nico.db :as db]
            [nico.rss :as rss]
            [nico.ui.about-dlg :as nua]
            [nico.ui.channel-panel :as nucp]
            [nico.ui.main-frame :as main-frame]
            [nico.ui.menu :as menu]
            [nico.ui.prefs-dlg :as prefs-dlg]
            [slide.core :as slc]
            [slide.logging :as sl]
            [seesaw.core :as sc]
            [seesaw.bind :as sb]
            [seesaw.border :as border]
            [seesaw.icon :as si])
  (:import [java.awt Dimension]
           [java.net URI]
           [java.util.concurrent Executors TimeUnit]
           [javax.imageio ImageIO]
           [com.github.sgr.slide LinkHandler LinkHandlers]
           [nico.cache ImageCache]
           [nico.ui AlertPanel PgmPanel PgmPanelLayout])
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

(let [sb (StringBuilder.)
      SEC-REST " sec rest"
      PER " / "
      PGMS-PERMIN " programs/min"
      NPGMS " programs"
      LAST-UPDATED "Last updated: "]
  (defn waiting-progress-str [^long sec]
    (locking sb
      (.setLength sb 0)
      (-> sb (.append sec) (.append SEC-REST) .toString)))
  (defn fetching-progress-str [^long acc ^long total]
    (locking sb
      (.setLength sb 0)
      (-> sb (.append acc) (.append PER) (.append total) .toString)))
  (defn rate-api-str [^long rate]
    (locking sb
      (.setLength sb 0)
      (-> sb (.append rate) (.append PGMS-PERMIN) .toString)))
  (defn npgms-str [^long npgms ^long total]
    (locking sb
      (.setLength sb 0)
      (if (pos? total)
        (-> sb (.append npgms) (.append PER) (.append total) (.append NPGMS) .toString)
        (-> sb (.append npgms) (.append NPGMS) .toString))))
  (defn last-updated-str [^String at]
    (locking sb
      (.setLength sb 0)
      (-> sb (.append LAST-UPDATED) (.append at) .toString))))

(defn- status-channel [frame]
  (let [cc (ca/chan)
        pool (Executors/newSingleThreadExecutor)
        icache (ImageCache. 2048 5 3
                            (.width PgmPanelLayout/ICON_SIZE)
                            (.height PgmPanelLayout/ICON_SIZE)
                            (ImageIO/read (io/resource "noimage.png")))
        {:keys [wpanel l-npgms l-last-updated
                spanel sresult-panel search-btn add-ch-btn l-search-status
                rss-btn rss-status rss-progress
                api-btn api-status api-rate]} (sc/group-by-id frame)
        browsers (atom nil)
        link-handlers (proxy [LinkHandlers] []
                        (getHandlerCount [] (count @browsers))
                        (getHandler [idx] (nth @browsers idx)))
        EMPTY-PROGRESS-STR ""
        NO-PGMS-PERMIN-STR "No programs/min"]
    (letfn [(link-handler [[name cmd]]
              (proxy [LinkHandler] [(if (= :default name) "Default Browser" name)]
                (browse [uri]
                  (if (= :default cmd)
                    (browse/browse-url (.. uri toURL toString))
                    (let [cmd (conj (tok/tokenize cmd) (.. uri toURL toString))]
                      (log/debugf "Open URL command: %s" (pr-str cmd))
                      (.start (ProcessBuilder. cmd)))))))
            (trim [s n]
              (if (and (instance? String s) (pos? n) (< n (.length s)))
                (-> (str (.substring s 0 n) "\n＜省略しています＞") String.)
                s))
            (pgm-panel-aux [pgm]
              (PgmPanel/create (:id pgm) (:title pgm) (:link pgm)
                               (:description pgm) (:owner_name pgm)
                               (:comm_name pgm) (:comm_id pgm)
                               (:type pgm) (:member_only pgm) (:open_time pgm)
                               (.getImageIcon icache (:thumbnail pgm))))
            (pgm-panel [pgm & {:keys [width height border]}]
              (let [p (pgm-panel-aux (if height pgm (update-in pgm [:description] trim 64)))]
                (.setLinkHandlers p link-handlers)
                (when width (.setWidth p width))
                (when height (.setHeight p height))
                (when border (.setBorder p (border/line-border :color :lightgray :thickness 1)))
                p))
            (cpanel [id]
              (-> (reduce #(assoc %1 (-> %2 sc/id-of name) %2) {} (.getComponents wpanel))
                  (get id)))
            (do-alert [msg thumbs duration]
              (let [imgs (doall (map #(.getImage icache %) thumbs))
                    apanel (do (AlertPanel/create msg imgs))]
                (da/alert apanel duration)))
            (update-pgms [id pgms title alert] ; 更新後のリスト内の番組数を返す。
              (let [pgm-lst (sc/select (cpanel id) [:#lst])
                    pnls (.getComponents pgm-lst)]
                (loop [pids (->> pnls (map #(.getId %)) set) ;; 各チャンネルパネルの番組ID集合
                       thumbs [] ;; 新規追加分サムネイルURL
                       futures [] ;; GUI更新処理(番組パネル追加)のFutureを保持
                       pgms pgms]
                  (if-let [pgm (first pgms)]
                    ;; [前半] pgmsのうちまだ無いものをパネルを作って追加する
                    (if (contains? pids (:id pgm))
                      (recur (disj pids (:id pgm)) thumbs futures (rest pgms))
                      (let [^Callable add-pgm-panel-fn #(let [ppanel (do (pgm-panel pgm))]
                                                          (sc/invoke-later (.add pgm-lst ppanel)))
                            ftr (.submit pool add-pgm-panel-fn)]
                        (recur pids (conj thumbs (:thumbnail pgm)) (conj futures ftr) (rest pgms))))
                    ;; [後半] pgmsを全て追加し終わった→不要なパネルの削除とアラート
                    (let [rpnls (doall (filter #(when (contains? pids (.getId %)) %) pnls))
                          cnt (count thumbs)]
                      (when (and alert (pos? cnt))
                        (let [msg (format "%d %s added to \"%s\"" cnt
                                          (if (= 1 cnt) "program is" "programs are") title)
                              ^Callable alert-fn #(do-alert msg thumbs 6000)]
                          (.submit pool alert-fn)))
                      (doseq [ftr futures] (.get ftr)) ;; 前半でキックしたGUI更新処理の完了を待つ
                      (sc/invoke-later
                       (doseq [rpnl rpnls] (.remove pgm-lst rpnl))
                       (doseq [rpnl rpnls] (.release rpnl))
                       (.validate pgm-lst))
                      (sc/invoke-now
                       (let [npgms (.getComponentCount pgm-lst)]
                         (sc/config! (sc/select (cpanel id) [:#control]) :border (format "%s (%d)" title npgms))
                         npgms)))))))]
      ;; このループではUIに関する状態を管理する。
      ;; PgmPanelがリンクを開く際に共通して用いるブラウザ情報およびLinkHandlersは
      ;; ループの外で保持するが(browsers, link-handlers)、
      ;; 変更はループ内でccからのコマンドを受けて実施する。(:set-browsers)
      (ca/go-loop [titles {}
                   npgms {}
                   alerts {}]
        (if-let [cmd (ca/<! cc)]
          ;;(log/tracef "UI-STATUS-LOOP: %s" (pr-str cmd))
          (let [status (:status cmd)
                n-titles (atom titles)
                n-npgms  (atom npgms)
                n-alerts (atom alerts)]
            (condp = status
              ;; このコマンドはループの外で保持されているagentを更新する。
              :set-browsers (let [bs (:browsers cmd)]
                              (log/debugf "set-browsers: %s" (pr-str bs))
                              (reset! browsers (map link-handler bs)))

              ;; 以下のコマンド群はループで管理している状態を更新する。
              :set-channel-alert (let [{:keys [id alert]} cmd]
                                   (swap! n-alerts assoc id alert))
              :set-channel-title (let [{:keys [id title]} cmd
                                       ctrl (sc/select (cpanel id) [:#control])
                                       npgm (or (get npgms id) 0)]
                                   (sc/invoke-later
                                    (sc/config! ctrl :border (format "%s (%d)" title npgm)))
                                   (swap! n-titles assoc id title))
              :dispose-channel (let [id (:id cmd)
                                     cp (cpanel id)
                                     pgm-lst (sc/select cp [:#lst])
                                     rpnls (doall (reduce conj [] (.getComponents pgm-lst)))]
                                 (sc/invoke-later
                                  (.removeAll pgm-lst)
                                  (.remove wpanel cp))
                                 (doseq [rpnl rpnls] (.release rpnl))
                                 (swap! n-titles dissoc id)
                                 (swap! n-npgms  dissoc id)
                                 (swap! n-alerts dissoc id))
              :searched (let [new-npgms (reduce (fn [m [id pgms]]
                                                  (let [title (get titles id)
                                                        alert (get alerts id)]
                                                  (assoc m id (update-pgms id pgms title alert))))
                                                {} (:results cmd))]
                          (swap! n-npgms merge new-npgms))

              ;; 以下のコマンド群はUIの更新のみ行い、状態更新はない。
              :db-stat (let [{:keys [npgms last-updated total]} cmd]
                         (sc/invoke-later
                          (sc/config! l-last-updated :text (last-updated-str last-updated))
                          (sc/config! l-npgms :text (npgms-str npgms total))))
              :fetching-rss (let [{:keys [page acc total]} cmd]
                              (sc/invoke-later
                               (sc/config! rss-status :text "fetching")
                               (if total
                                 (sc/config! rss-progress :value acc :max total)
                                 (sc/config! rss-progress :value acc))
                               (.setString rss-progress (if total
                                                          (fetching-progress-str acc total)
                                                          (str acc)))))
              :searched-ondemand (let [{:keys [cnt results]} cmd
                                       nresults (count results)
                                       s (cond
                                          (zero? cnt) "No programs found."
                                          (= 1 cnt) "A program found."
                                          (= cnt nresults) (str cnt " programs found.")
                                          :else (format "%d programs found. Top %d results are as follows:"
                                                        cnt nresults))
                                       pnls (map #(pgm-panel % :width 280 :height 140 :border true) results)
                                       rpnls (doall (reduce conj [] (.getComponents sresult-panel)))]
                                   (sc/invoke-now
                                    (sc/value! l-search-status s)
                                    (.removeAll sresult-panel)
                                    (doseq [pnl pnls] (.add sresult-panel pnl))
                                    (.validate sresult-panel)
                                    (sc/config! add-ch-btn :enabled? (pos? nresults))
                                    (sc/config! search-btn :enabled? true))
                                   (doseq [rpnl rpnls] (.release rpnl)))
              :waiting-rss (let [{:keys [sec total]} cmd]
                             (sc/invoke-later
                              (sc/config! rss-status :text "waiting")
                              (sc/config! rss-progress :value sec :max total)
                              (.setString rss-progress (waiting-progress-str sec))))
              :started-rss (sc/invoke-later
                            (sc/config! rss-status :text "running")
                            (sc/config! rss-progress :value 0 :max 100)
                            (.setString rss-progress EMPTY-PROGRESS-STR)
                            (sc/config! rss-btn :icon "stop.png"))
              :stopped-rss (sc/invoke-later
                            (sc/config! rss-status :text "stand-by")
                            (sc/config! rss-progress :value 0 :max 100)
                            (.setString rss-progress EMPTY-PROGRESS-STR)
                            (sc/config! rss-btn :icon "start.png"))
              :enabled-api  (sc/invoke-later (sc/config! api-btn :enabled? true))
              :disabled-api (sc/invoke-later (sc/config! api-btn :enabled? false))
              :starting-api (sc/invoke-later
                             (sc/config! api-btn :enabled? false)
                             (sc/config! api-status :text "starting")
                             (sc/config! api-rate :text NO-PGMS-PERMIN-STR))
              :started-api (sc/invoke-later
                            (sc/config! api-btn :enabled? false)
                            (sc/config! api-status :text "listening")
                            (sc/config! api-rate :text NO-PGMS-PERMIN-STR))
              :rate-api (sc/invoke-later
                         (sc/config! api-rate :text (rate-api-str (:rate cmd))))
              :stopped-api (sc/invoke-later
                            (sc/config! api-btn :enabled? true)
                            (sc/config! api-status :text "stopped")
                            (sc/config! api-rate :text NO-PGMS-PERMIN-STR))
              (log/warnf "caught an unknown status: %s" (pr-str cmd)))
            (recur @n-titles @n-npgms @n-alerts))
          (do
            (.shutdown pool)
            (.awaitTermination pool 5 TimeUnit/SECONDS)
            (log/info "closed status channel"))))
      cc)))

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
        cc-status (status-channel frame) ;; status は以下のチャネルからの状態情報をUIに反映
        cc-db (db/boot cc-status) ;; db -> status
        cc-rss (rss/boot cc-status cc-db) ;; rss -> status, db
        cc-api (api/boot cc-status cc-db) ;; api -> status, db
        {:keys [wpanel spanel search-btn add-ch-btn l-search-status]} (sc/group-by-id frame)
        cfg (config/load-config)]

    (ca/>!! cc-status {:status :set-browsers :browsers (:browsers cfg)})

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
                                 (ca/>! cc-status {:status :set-browsers :browsers new-browsers-cfg}))
                               (recur (merge cfg new-cfg) (disj async-ops op)))
              :add-channel (let [ch (:channel c)
                                 id (:id ch)]
                             (add-channel! ch wpanel cc)
                             (condp = (:type ch)
                               :kwd (let [{:keys [title query target]} ch]
                                      (ca/>! cc-db {:cmd :set-query-kwd :id id :query query :target target})
                                      (ca/>! cc-status {:status :set-channel-title :id id :title title}))
                               :comm (let [{:keys [id email passwd]} ch]
                                       (ca/>! cc-api {:cmd :login :id id :email email :passwd passwd})))
                             (ca/>! cc-status {:status :set-channel-alert :id id :alert (:alert ch)})
                             (recur (update-in cfg [:channels] conj ch) (disj async-ops op)))
              :dispose-channel (let [id (:id c)]
                                 (when-let [idx (index-channel id (:channels cfg))]
                                   (let [ch (nth (:channels cfg) idx)]
                                     (condp = (:type ch)
                                       :kwd (ca/>! cc-db {:cmd :rem-query :id id})
                                       :comm (ca/>! cc-api {:cmd :rem-alert-status :id id}))
                                     (ca/>! cc-status {:status :dispose-channel :id id})
                                     (recur (update-in cfg [:channels] disvec idx) async-ops))))
              :update-channel-alert (let [{:keys [id alert]} c
                                          idx (index-channel id (:channels cfg))]
                                      (when-let [ch (channel id (:channels cfg))]
                                        (ca/>! cc-status {:status :set-channel-alert :id id :alert alert})
                                        (recur (update-in cfg [:channels] assoc idx (assoc ch :alert alert))
                                               async-ops)))
              :update-channel (let [ch (:channel c)
                                    id (:id ch)]
                                (when-let [idx (index-channel id (:channels cfg))]
                                  (condp = (:type ch)
                                    :kwd (let [{:keys [title query target]} ch]
                                           (ca/>! cc-db {:cmd :set-query-kwd :id id :query query :target target})
                                           (ca/>! cc-status {:status :set-channel-title :id id :title title}))
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
                                          (ca/>! cc-status {:status :set-channel-title :id id :title title}))
                                   :comm (let [{:keys [id email passwd]} ch]
                                           (ca/>! cc-api {:cmd :login :id id :email email :passwd passwd})))
                                 (ca/>! cc-status {:status :set-channel-alert :id (:id ch) :alert (:alert ch)}))
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
                (ca/close! cc-status)
                (when (pos? (count async-ops))
                  (log/warnf "Unfinished operations: %s" (pr-str async-ops)))
                (da/close-alert)
                (log/info "Store the config now...")
                (config/store-config cfg)
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
