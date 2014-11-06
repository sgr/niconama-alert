;; -*- coding: utf-8-unix -*-
(ns nico.api
  (:require [clojure.core.async :as ca]
            [clojure.data.zip.xml :as dzx]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [nico.net :as net]
            [nico.pgm :as pgm]
	    [nico.scrape :as ns]
            [nico.string :as s])
  (:import [java.io BufferedReader InputStreamReader IOException OutputStreamWriter]
           [java.net Socket]))

(defn get-alert-status [email passwd]
  (letfn [(get-ticket [email passwd] ;; 認証APIでチケットを得る
            (with-open [is (-> "https://secure.nicovideo.jp/secure/login?site=nicolive_antenna"
                               (net/http-post {:form-params {:mail email :password passwd} :as :stream})
                               :body)]
              (let [zipped (-> is xml/parse zip/xml-zip)]
                (dzx/xml1-> zipped :ticket dzx/text))))
          (get-alert-status1 [ticket]
            (with-open [is (-> (format "http://live.nicovideo.jp/api/getalertstatus?ticket=%s" ticket)
                               (net/http-get {:as :stream})
                               :body)]
              (let [zipped (-> is xml/parse zip/xml-zip)]
                {:user_id (dzx/xml1-> zipped :user_id dzx/text)
                 :user_name (dzx/xml1-> zipped :user_name dzx/text)
                 :comms (set (dzx/xml-> zipped :communities :community_id dzx/text))
                 :addr (dzx/xml1-> zipped :ms :addr dzx/text)
                 :port (Integer/parseInt (dzx/xml1-> zipped :ms :port dzx/text))
                 :thrd (dzx/xml1-> zipped :ms :thread dzx/text)})))]
    (try
      (get-alert-status1 (get-ticket email passwd))
      (catch Exception e
        (log/errorf e "failed login: %s" email)))))

(defn- get-stream-info [pid]
  (with-open [is (-> (format "http://live.nicovideo.jp/api/getstreaminfo/lv%s" pid)
                     (net/http-get {:as :stream})
                     :body)]
    (-> is xml/parse zip/xml-zip)))

(defn- create-pgm-from-getstreaminfo
  "getstreaminfoで得られた情報から番組情報を生成する。が、足りない情報がポロポロあって使えない・・・"
  [zipped-res fetched_at]
  (let [id (dzx/xml1-> zipped-res :request_id dzx/text)]
    (pgm/->Pgm
     id
     (dzx/xml1-> zipped-res :streaminfo :title dzx/text)
     nil ; open_time
     nil ; start_time
     (dzx/xml1-> zipped-res :streaminfo :description dzx/text)
     nil ;category
     (str "http://live.nicovideo.jp/watch/" id)
     (dzx/xml1-> zipped-res :communityinfo :thumbnail dzx/text)
     nil ;owner_name
     nil ;member_only
     (dzx/xml1-> zipped-res :streaminfo :provider_type dzx/text)
     (dzx/xml1-> zipped-res :communityinfo :name dzx/text)
     (keyword (dzx/xml1-> zipped-res :streaminfo :default_community dzx/text))
     fetched_at
     fetched_at)))

(defn- parse-chat-str
  "Return [id cid uid] or nil"
  [^String chat-str]
  (try
    (let [chat (-> chat-str s/utf8stream xml/parse)
          c (:content chat)]
      (when (and c (= :chat (:tag chat)))
        (.split ^String (first c) ",")))
    (catch Exception e
      (log/warnf "parse error: %s" chat-str))))

(defn boot
  "ニコ生アラートAPIを通じて番組情報を取得するリスナーを生成し、コントロールチャネルを返す。
   引数oc-uiにはリスナーの状態を受け取るチャネルを、
   引数oc-dbにはリスナーが受信した番組情報を受け取るチャネルをそれぞれ指定する。

   アウトプットチャネルoc-uiには次のステータスが出力される。
   :enabled-api 有効である（開始可能である）
          {:status :enabled-api}
   :disabled-api 無効である（開始できない）
          {:status :disabled-api}
   :starting-api 開始中（接続中）である。
          {:status :starting-api}
   :started-api 開始した。
          {:status :started-api}
   :stopped-api 終了した。
          {:status :stopped-api}
   :rate-api 分あたりの番組情報受信数。RATE-UPDATE-INTERVAL(ミリ秒)間隔で通知。
          {:status :rate-api :rate [分あたりの番組情報受信数]}

   アウトプットチャネルoc-dbには番組情報が出力される。
   :add-pgm 取得した番組情報。取得する番組はユーザーの所属するコミュニティに限られる。
          {:cmd :add-pgm :pgm [番組情報]}

   コントロールチャネルは次のコマンドを受理する。
   :login ログイン。
          {:cmd :login :id [チャネルID] :email [メールアドレス] :passwd [パスワード]}
   :start リスナーを開始。有効なalert-statusが登録されていない場合は無視される。
          {:cmd :start}
   :restart リスナーを再起動。
          {:cmd :restart :retry [リトライ回数]}
   :set-alert-status alert-statusを登録する。これはget-alert-statusで取得できるmapである。
          {:cmd :set-alert-status, :id [チャネルID], :alert-status [alert-statusオブジェクト]}
   :rem-alert-status alert-statusを削除する。
          {:cmd :rem-alert-status, :id [チャネルID]}

   また、次のコマンドが内部的に使用される。
   :pgm 番組情報取得要求。
          {:cmd :pgm, :pid [番組ID] :cid [コミュID] :uid [ユーザID] :received [取得時刻]}
   "
  [oc-ui oc-db]
  (let [UPDATE-INTERVAL 5000
        FETCH-INTERVAL 10000
        RETRY-LIMIT 20
        cc (ca/chan)
        cc-fetcher (ca/chan (ca/dropping-buffer 64))]
    (letfn [(listen [alert-status]
              (with-open [sock (doto (Socket. ^String (:addr alert-status) ^int (:port alert-status))
                                 (.setSoTimeout 30000))
                          wtr (OutputStreamWriter. (.getOutputStream sock))
                          ^BufferedReader rdr (BufferedReader. (InputStreamReader. (.getInputStream sock) "UTF8"))]
                (ca/>!! cc {:cmd :connected})
                (let [q (format "<thread thread=\"%s\" version=\"20061206\" res_from=\"-1\"/>\0"
                                (:thrd alert-status))] ; res_fromを-1200にすると、全ての番組を取得するらしい。
                  (doto wtr (.write q) (.flush)))
                (loop [c (.read rdr) ^StringBuilder s (StringBuilder.)]
                  (condp = c
                    -1 (do
                         (log/info "******* CONNECTION CLOSED *******")
                         :disconnected)
                    0  (let [received (System/currentTimeMillis)]
                         (if-let [[id cid uid] (map s/nstr (parse-chat-str (.toString s)))]
                           (ca/>!! cc {:cmd :pgm :pid (str "lv" id) :cid cid :uid uid :received received})
                           (log/debugf "it isn't chat: %s" s))
                         (recur (.read rdr) (StringBuilder.)))
                    (recur (.read rdr) (.append s (char c)))))))

            (connect [alert-status retry]
              (ca/go
                (try
                  (log/debug "CONNECTING")
                  (listen alert-status)
                  (log/debug "DISCONNECTED")
                  {:cmd :restart :retry 0}
                  (catch Exception e
                    (log/warnf "DISCONNECTED caused by: %s" (class e))
                    {:cmd :restart :retry (inc retry)}))))
            (gen-comms [alert-statuses]
                (apply set/union (map :comms (vals alert-statuses))))]

      ;; fetcher
      (ca/go-loop [c (ca/<! cc-fetcher)]
        (if c
          (let [{:keys [pid cid received]} c]
            (when (and pid cid received)
              (when-let [pgm (ns/scrape-pgm pid cid received)]
                (ca/>! oc-db {:cmd :add-pgms :pgms (list pgm) :force-search true})
                (Thread/sleep FETCH-INTERVAL)))
            (recur (ca/<! cc-fetcher)))
          (log/info "Closed API fetcher channel")))

      (ca/go-loop [user {:comms #{}
                         :as {}
                         :queue {}}
                   rate {:rate (list)
                         :ch nil}
                   listener nil]
        (let [chs (->> (conj (vals (:queue user)) cc (:ch rate) listener) (remove nil?))
              [c ch] (ca/alts! chs)]
          ;;(log/trace "API-LOOP: " (pr-str c))
          (if c
            (condp = (:cmd c)
              :login (let [{:keys [id email passwd]} c
                           lc (ca/thread
                                (if-let [as (get-alert-status email passwd)]
                                  (let [user_name (:user_name as)]
                                    (ca/>!! oc-ui {:status :set-channel-title :id id :title user_name})
                                    {:cmd :set-alert-status :id id :alert-status as})
                                  (do
                                    (ca/>!! oc-ui {:status :set-channel-title :id id :title "(login failed)"})
                                    {:cmd :rem-alert-status :id id})))]
                       (ca/>! oc-ui {:status :set-channel-title :id id :title "(logging in...)"})
                       (recur (update-in user [:queue] assoc id lc) rate listener))
              :set-alert-status (let [{:keys [id alert-status]} c
                                      new-as (assoc (:as user) id alert-status)
                                      new-comms (gen-comms new-as)
                                      new-q (dissoc (:queue user) id)]
                                  (ca/close! ch)
                                  (ca/>! oc-db {:cmd :set-query-user :id id :comms (:comms alert-status)})
                                  (when (and (nil? listener) (pos? (count new-as)))
                                    (ca/>! oc-ui {:status :enabled-api}))
                                  (recur {:comms new-comms :as new-as :queue new-q} rate listener))
              :rem-alert-status (let [id (:id c)
                                      new-as (dissoc (:as user) id)
                                      new-comms (gen-comms new-as)
                                      new-q (dissoc (:queue user) id)]
                                  (when (not= cc ch) (ca/close! ch))
                                  (ca/>! oc-db {:cmd :rem-query :id id})
                                  (when (zero? (count new-as))
                                    (ca/>! oc-ui {:status :disabled-api}))
                                  (recur {:comms new-comms :as new-as :queue new-q} rate listener))
              :start (if (and (nil? listener) (pos? (count (:as user))))
                       (do
                         (ca/>! oc-ui {:status :starting-api})
                         (recur user rate (connect (-> (:as user) vals first) 0)))
                       (do
                         (log/warnf "couldn't start [%d, %s]" (count (:as user)) (pr-str listener))
                         (recur user rate listener)))
              :restart (let [retry (:retry c)
                             cnt (count (:as user))]
                         (ca/close! listener)
                         (ca/>! oc-ui (if (and (> RETRY-LIMIT retry) (pos? cnt))
                                            {:status :disabled-api}
                                            {:status :stopped-api}))
                         (recur user rate
                                (when (and (> RETRY-LIMIT retry) (pos? cnt))
                                  (log/infof "retry connecting via API (%d)" retry)
                                  (Thread/sleep (* retry 1500))
                                  (connect (-> (:as user) vals first) retry))))
              :connected (do
                           (ca/>! oc-ui {:status :started-api})
                           (recur user (assoc rate :ch (ca/timeout UPDATE-INTERVAL)) listener))
              :pgm (let [{:keys [pid cid uid received]} c]
                     (when (contains? (:comms user) cid)
                       (ca/>! cc-fetcher c))
                     (recur user (update-in rate [:rate] conj received) listener)))

            (cond ;; cがnilの場合はチャネルを見る。
             (= ch (:ch rate)) (let [now (System/currentTimeMillis)
                                     rate-updated (filter #(> % (- now 60000)) (:rate rate))]
                                 (ca/>! oc-ui {:status :rate-api :rate (count rate-updated)})
                                 (recur user {:rate rate-updated :ch (ca/timeout UPDATE-INTERVAL)} listener))
             (not= ch cc) (do
                            (log/warn "other channel closed: " (pr-str ch))
                            (recur user rate listener))
             :else (log/infof "Closed API control channel"))))))
    cc))
