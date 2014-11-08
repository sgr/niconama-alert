;; -*- coding: utf-8-unix -*-
(ns nico.status
  (:require [clojure.core.async :as ca]
            [clojure.data :as cd]
            [clojure.java.browse :as browse]
            [clojure.tools.logging :as log]
            [desktop-alert :as da]
            [input-parser.tokenizer :as tok]
            [seesaw.core :as sc]
            [seesaw.border :as border])
  (:import [java.net URI]
           [com.github.sgr.slide LinkHandler LinkHandlers]
           [nico.ui AlertPanel PgmList PgmPanel]))

(defn boot
  "このアプリケーションの現在の状態をGUIに反映するためのコントロールチャネルを返す。
   処理結果は全てGUIに反映されるため、アウトプットチャネルはない。(終点)
   コントロールチャネルは他のチャネルからの出力を受理する。
   (core, db, rss, apiの各チャネルのドキュメントを確認)"
  [frame]
  (let [cc (ca/chan)
        browsers (atom nil)
        link-handlers (proxy [LinkHandlers] []
                        (getHandlerCount [] (count @browsers))
                        (getHandler [idx] (nth @browsers idx)))
        EMPTY-PROGRESS-STR ""
        NO-PGMS-PERMIN-STR "No programs/min"
        LINE-SEPARATOR (System/getProperty "line.separator")
        {:keys [wpanel l-npgms l-last-updated
                spanel sresult-panel search-btn add-ch-btn l-search-status
                rss-btn rss-status rss-progress
                api-btn api-status api-rate]} (sc/group-by-id frame)]
    (letfn [(link-handler [[name cmd]]
              (proxy [LinkHandler] [(if (= :default name) "Default Browser" name)]
                (browse [uri]
                  (if (= :default cmd)
                    (browse/browse-url (.. uri toURL toString))
                    (let [cmd (conj (tok/tokenize cmd) (.. uri toURL toString))]
                      (log/debugf "Open URL command: %s" (pr-str cmd))
                      (.start (ProcessBuilder. cmd)))))))
            (trim [^String s n]
              (if (and (pos? n) (< n (count s)))
                (-> (str (.substring s 0 n) LINE-SEPARATOR "＜省略しています＞") String.)
                s))
            (pgm-panel [pgm & {:keys [width height border]}]
              (let [^PgmPanel p (PgmPanel/create (:id pgm) (:title pgm) (:link pgm) (trim (:description pgm) 64)
                                                 (:owner_name pgm) (:comm_name pgm) (:comm_id pgm) (:type pgm)
                                                 (:member_only pgm) (:open_time pgm) (:thumbnail_image pgm))]
                (.setLinkHandlers p link-handlers)
                (when width (.setWidth p width))
                (when height (.setHeight p height))
                (when border (.setBorder p (border/line-border :color :lightgray :thickness 1)))
                p))
            (update-panel [^PgmPanel p pgm]
              (when (< (-> p .getTitle count) (-> pgm :title count))
                (.setTitle p (:title pgm)))
              (when (< (-> p .getDescription count) (-> pgm :description (trim 64) count))
                (.setDescription p (-> pgm :description (trim 64))))
              (when (< (.getOpenTime p) (:open_time pgm))
                (.setOpenTime p (:open_time pgm)))
              (when (and (nil? (.getThumbnail p)) (:thumbnail_image pgm))
                (.setThumbnail p (:thumbnail_image pgm))))
            (cpanel [id]
              (->> (.getComponents wpanel)
                   (filter #(= id (-> % sc/id-of name)))
                   first))
            (do-alert [title thumbs]
              (when (pos? (count thumbs))
                (let [cnt (count thumbs)
                      msg (format "%d %s added to \"%s\"" cnt (if (= 1 cnt) "program is" "programs are") title)
                      apanel (do (AlertPanel/create msg thumbs))]
                  (da/alert apanel 6000))))
            (update-pgms [id pgms title alert] ; 更新後のリスト内の番組数を返す。
              (let [^PgmList pgm-lst (sc/select (cpanel id) [:#lst])
                    pmap (reduce #(assoc %1 (:id %2) %2) {} pgms)
                    pnls (.getComponents pgm-lst)
                    [rpids npids upids] (cd/diff (set (map #(.getId %) pnls)) (set (map :id pgms)))
                    npnls (->> pgms (filter #(contains? npids (:id %))) (map pgm-panel)) ; 追加パネル
                    rpnls (->> pnls (filter #(contains? rpids (.getId %))))  ; 削除パネル
                    upnls (->> pnls (filter #(contains? upids (.getId %))))] ; 更新するかもパネル
                (when alert
                  (do-alert title (->> pgms (filter #(contains? npids (:id %))) (map :thumbnail_image))))
                (sc/invoke-now
                 (doseq [rpnl rpnls] (.remove pgm-lst rpnl) (.release rpnl))
                 (doseq [upnl upnls] (update-panel upnl (get pmap (.getId upnl))))
                 (doseq [npnl npnls] (.add pgm-lst npnl))
                 (.validate pgm-lst)
                 (let [npgms (.getComponentCount pgm-lst)]
                   (sc/config! (sc/select (cpanel id) [:#control]) :border (format "%s (%d)" title npgms))
                   npgms))))]

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

              ;; 以下のコマンド群はUIへの反映のみ、ループ状態更新はない。
              :db-stat (let [{:keys [npgms last-updated total]} cmd]
                         (sc/invoke-later
                          (sc/config! l-last-updated :text (str "Last updated: " last-updated))
                          (sc/config! l-npgms :text (if (and total (pos? total))
                                                      (str npgms " / " total " programs")
                                                      (str npgms " programs")))))
              :fetching-rss (let [{:keys [page acc total]} cmd]
                              (sc/invoke-later
                               (sc/config! rss-status :text "fetching")
                               (if total
                                 (sc/config! rss-progress :value acc :max total)
                                 (sc/config! rss-progress :value acc))
                               (.setString rss-progress (if total (str acc " / " total) (str acc)))))
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
                              (.setString rss-progress (str sec " sec rest"))))
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
                         (sc/config! api-rate :text (str (:rate cmd) " programs/min")))
              :stopped-api (sc/invoke-later
                            (sc/config! api-btn :enabled? true)
                            (sc/config! api-status :text "stopped")
                            (sc/config! api-rate :text NO-PGMS-PERMIN-STR))
              (log/warnf "caught an unknown status: %s" (pr-str cmd)))
            (recur @n-titles @n-npgms @n-alerts))
          (log/info "closed status channel")))
      cc)))
