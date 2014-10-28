;; -*- coding: utf-8-unix -*-
(ns nico.status
  (:require [clojure.core.async :as ca]
            [clojure.java.browse :as browse]
            [clojure.tools.logging :as log]
            [desktop-alert :as da]
            [input-parser.tokenizer :as tok]
            [seesaw.core :as sc]
            [seesaw.border :as border])
  (:import [java.net URI]
           [java.util.concurrent Executors TimeUnit]
           [javax.swing ImageIcon]
           [com.github.sgr.slide LinkHandler LinkHandlers]
           [nico.ui AlertPanel PgmPanel]))

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

(defn boot
  "このアプリケーションの現在の状態をGUIに反映するためのコントロールチャネルを返す。
   処理結果は全てGUIに反映されるため、アウトプットチャネルはない。(終点)
   コントロールチャネルは他のチャネルからの出力を受理する。
   (core, db, rss, apiの各チャネルのドキュメントを確認)"
  [frame]
  (let [cc (ca/chan)
        pool (Executors/newSingleThreadExecutor)
        browsers (atom nil)
        link-handlers (proxy [LinkHandlers] []
                        (getHandlerCount [] (count @browsers))
                        (getHandler [idx] (nth @browsers idx)))
        EMPTY-PROGRESS-STR ""
        NO-PGMS-PERMIN-STR "No programs/min"
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
            (trim [s n]
              (if (and (instance? String s) (pos? n) (< n (.length s)))
                (-> (str (.substring s 0 n) "\n＜省略しています＞") String.)
                s))
            (pgm-panel-aux [pgm]
              (PgmPanel/create (:id pgm) (:title pgm) (:link pgm)
                               (:description pgm) (:owner_name pgm)
                               (:comm_name pgm) (:comm_id pgm)
                               (:type pgm) (:member_only pgm) (:open_time pgm)
                               (ImageIcon. (:thumbnail pgm))))
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
              (let [apanel (do (AlertPanel/create msg thumbs))]
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

              ;; 以下のコマンド群はUIへの反映のみ、ループ状態更新はない。
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
