;; -*- coding: utf-8-unix -*-
(ns nico.rss
  (:require [clojure.core.async :as ca]
            [clojure.data.zip :as dz]
            [clojure.data.zip.xml :as dzx]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [nico.net :as net]
            [nico.pgm :as pgm]
            [nico.scrape :as scrape]
            [nico.string :as s])
  (:import [java.io BufferedReader InputStream InputStreamReader]
           [java.util Locale]
           [java.util.concurrent TimeUnit]
           [org.xml.sax InputSource]
           [org.apache.commons.lang3.time FastDateFormat]
           [org.htmlcleaner HtmlCleaner]))

(let [cleaner (HtmlCleaner.)]
  (defn- remove-tag [^String s]
    (when-not (cs/blank? s)
      (-> (.clean cleaner s) .getText .toString))))

(defn- child-elements [node tag]
  (->> (:content node) (filter #(= tag (:tag %)))))

(defn- child-content [node tag]
  (-> node (child-elements tag) first :content first))

(defn- get-child-attr [node tag attr]
  (-> node (child-elements tag) first :attrs attr))

(defn- parse-date [s fmt] (-> fmt (.parse s) .getTime))

(let [fmt (FastDateFormat/getInstance "yyyy-MM-dd HH:mm:ss")]
  (defn create-official-pgm [item fetched_at]
    (try
      (let [id (-> item (child-content :guid) s/nstr)
            title (-> item (child-content :title) (s/unescape :html) s/nstr)
            open_time (-> item (child-content :nicolive:open_time) (parse-date fmt))
            start_time (-> item (child-content :nicolive:start_time) (parse-date fmt))
            description (if-let [dstr (child-content item :description)]
                          (-> dstr (s/unescape :html) remove-tag s/del-dup s/nstr) "")
            category "" ; ない
            link (-> item (child-content :link) s/nstr)
            thumbnail (-> item (get-child-attr :media:thumbnail :url) s/nstr)
            owner_name "" ; ない
            member_only 0 ; ない
            type (if-let [type-str (child-content item :nicolive:type)]
                   (condp = type-str
                     "community" 0
                     "channel" 1
                     2) 2) ; "official"
            comm_name "" ; ない
            comm_id nil] ; ない
        (if (and (not-every? cs/blank? [id title link thumbnail]) description open_time start_time)
          (pgm/->Pgm id title open_time start_time description category link thumbnail owner_name
                     member_only type comm_name comm_id fetched_at fetched_at)
          (log/warnf "couldn't create official pgm: [%s %s %s %s %s, %s %s]"
                     id title description link thumbnail open_time start_time)))
      (catch Exception e
        (log/warnf "failed creating pgm from official RSS: %s" (-> item pr-str s/nstr))))))

(let [fmt (FastDateFormat/getInstance "EEE, dd MMM yyyy HH:mm:ss Z" Locale/ENGLISH)]
  (defn create-pgm [item fetched_at]
    (try
      (let [id (-> item (child-content :guid) s/nstr)
            title (-> item (child-content :title) (s/unescape :html) s/nstr)
            open_time (-> item (child-content :pubDate) (parse-date fmt))
            start_time open_time
            description (if-let [dstr (child-content item :description)]
                          (-> dstr (s/unescape :html) remove-tag s/del-dup s/nstr) "")
            category (->> (child-elements item :category)
                          (map #(-> % :content first))
                          (cs/join ",")
                          s/nstr)
            link (-> item (child-content :link) s/nstr)
            thumbnail (-> item (get-child-attr :media:thumbnail :url) s/nstr)
            owner_name (-> item (child-content :nicolive:owner_name) s/nstr)
            member_only (-> item (child-content :nicolive:member_only) Boolean/parseBoolean {true 1 false 0})
            type (if-let [type-str (child-content item :nicolive:type)]
                   (condp = type-str
                     "community" 0
                     "channel" 1
                     2) 2) ; "official"
            comm_name (-> item (child-content :nicolive:community_name) s/nstr)
            comm_id (-> item (child-content :nicolive:community_id) s/nstr)]
        ;;(when (cs/blank? title) (log/infof "title is blank [%s -> %s]" item title))
        (if (and (not-every? cs/blank? [id title link thumbnail]) description open_time start_time)
          (pgm/->Pgm id title open_time start_time description category link thumbnail owner_name
                     member_only type comm_name comm_id fetched_at fetched_at)
          (log/debugf "couldn't create pgm: [%s %s %s %s %s, %s %s]"
                      id title description link thumbnail open_time start_time)))
      (catch Exception e
        (log/warnf "failed creating pgm from RSS: %s" (-> item pr-str s/nstr))))))

(defn- get-programs-count
  "get the total programs count."
  [rss]
  (when-let [total-count (first (dzx/xml-> (zip/xml-zip rss) :channel :nicolive:total_count dzx/text))]
    (try
      (Integer/parseInt total-count)
      (catch NumberFormatException e
        (log/errorf "failed fetching RSS for get programs count: %s" rss)))))

(defn- items [rss]
  (let [nodes-child (dzx/xml-> (zip/xml-zip rss) :channel dz/children)]
    (for [x nodes-child :when (= :item (:tag (first x)))] (first x))))

(defn extract [rss pgm-fn]
  (let [now (System/currentTimeMillis)]
    (->> (items rss)
         (map #(pgm-fn % now))
         (filter #(and % (and (:id %) (:title %) (:open_time %) (:start_time %)))))))

(defn- get-nico-rss [^String url extract-fn]
  (if-let [response (net/http-get url {:as :stream})]
    (condp = (:status response)
      200 (try
            (with-open [^InputStream is (-> url (net/http-get {:as :stream}) :body)
                        ^InputStreamReader isr (s/clean-reader is)
                        ^BufferedReader br (BufferedReader. isr)]
              (when-let [rss (xml/parse (InputSource. br))]
                (extract-fn rss)))
            (catch Exception e
              (log/warnf "failed extracting RSS from response %s, %s" url (.getMessage e))))
      404 (log/debugf "The RSS is not found %s" (pr-str response))
      (log/warnf "failed fetching RSS %s" (pr-str response)))
    (log/warnf "timeouted fetching RSS %s" url)))

(defn- get-programs-from-rss
  ([] ; ページなしは公式の番組取得。RSSフォーマットが異なる。
     (get-nico-rss "http://live.nicovideo.jp/rss" #(if % (extract % create-official-pgm) [])))
  ([page] ; pageは1以上。0で問い合わせると1と同じ結果が返る。また、カテゴリ無しはcommonカテゴリと同じ結果が返る。
     {:pre [(pos? page)]}
     (get-nico-rss (format "http://live.nicovideo.jp/recent/rss?p=%d" page)
                   #(if % [(get-programs-count %) (extract % create-pgm)] [nil []])))
  ([page category] ; pageは1以上。0で問い合わせると1と同じ結果が返るみたい。
     {:pre [(pos? page)]}
     (get-nico-rss (format "http://live.nicovideo.jp/recent/rss?tab=%s&p=%d" category page)
                   #(if % [(get-programs-count %) (extract % create-pgm)] [nil []]))))

(defn- fetch
  ([] ;; 公式放送RSS
     (let [pgms  (get-programs-from-rss)
           npgms (count pgms)
           real-total (or (scrape/scrape-total) 0)]
       (if (pos? npgms)
         {:page 0 :cats nil :result :success :npgms npgms
          ;; このcmd-db、vectorだとIOCマクロが作る状態配列(AtomicReferenceArray)にArrayChunkとして、
          ;; その下にcmd-dbのオブジェクトが参照されたまま残ってしまい、奇妙な形のメモリリークとなる。
          ;; なぜvectorだとダメなのかまでは追求しきれていない。
          :cmd-db (if (pos? real-total)
                    (list {:cmd :set-total :total (+ npgms real-total)}
                          {:cmd :add-pgms :pgms pgms :force-search false})
                    {:cmd :add-pgms :pgms pgms :force-search false})
          :cmd-ui {:status :fetching-rss :page 0 :acc npgms :total nil}}
         {:page 0 :cats nil :result :error :npgms npgms
          :cmd-db {:cmd :set-total :total real-total}
          :cmd-ui {:status :fetching-rss :page 0 :acc 0 :total nil}})))
  ([page cats] ;; ユーザー生放送RSS
     (let [[ncats pgms] (reduce (fn [[m pgms] [category [acc total]]]
                                  (if (and (not= 1 page) (>= acc total))
                                    [(assoc m category [acc total]) pgms]
                                    (let [[ctotal cpgms] (get-programs-from-rss page (name category))]
                                      [(assoc m category [(+ acc (count cpgms))
                                                          (if ((every-pred number? pos?) ctotal) ctotal total)])
                                       (concat pgms cpgms)])))
                                [{} []] (or cats {:common [0 0]     ; 一般
                                                  :try    [0 0]     ; やってみた
                                                  :live   [0 0]     ; ゲーム
                                                  :req    [0 0]     ; 動画紹介
                                                  :r18    [0 0]     ; R-18
                                                  :face   [0 0]     ; 顔出し
                                                  :totu   [0 0]}))  ; 凸待ち
           npgms (count pgms)
           acc (->> ncats vals (map first) (apply +))
           total (->> ncats vals (map second) (apply +))]
       {:page page :result :success :cats ncats :npgms npgms
        :cmd-db {:cmd :add-pgms :pgms pgms :force-search (-> npgms pos? not)}
        :cmd-ui {:status :fetching-rss :page page :acc acc :total total}})))

(defn boot
  "ニコ生RSSを通じて番組情報を取得するfetcherを生成し、コントロールチャネルを返す。
   引数oc-uiにはfetcherからの状態を受け取るチャネルを、
   引数oc-dbにはfetcherが得た番組情報を受け取るチャネルをそれぞれ指定する。

   アウトプットチャネルoc-uiには次のステータスが出力される。
   :started-rss 開始した。
          {:status :started-api}
   :stopped-rss 終了した。
          {:status :stopped-api}
   :waiting-rss 次の取得サイクル開始まで待機中。待機間隔はWAITING-INTERVAL(秒)。
          {:status :waiting-rss :sec [残り秒] :total [全体待機時間]}
   :fetching-rss RSS取得中。得られた番組情報つき。
          {:status :fetching-rss :page [取得したページ] :acc [今サイクルで取得した番組数計] :total [総番組数]}

   アウトプットチャネルoc-dbには番組情報が出力される。
   :add-pgms 番組情報追加。上の:fetching-rssと同時に発信される。
          {:cmd :add-pgms :pgms [番組情報] :force-search [番組情報検索するか]}
   :set-total 総番組数を設定する。
          {:cmd :set-total :total [公式・チャンネルの番組数 + ニコ生から得た総番組数] }

   コントロールチャネルは次のコマンドを受理する。
   :act 今のfetcherの状態によって開始または終了するトグル動作。
          {:cmd :act}

   また、次のコマンドが内部的に使用される。
   :fetch 指定されたページのRSSを取得する。0ページと1ページ以降は異なる。
          {:cmd :fetch} ; 0ページ目は公式・チャンネルの番組
          {:cmd :fetch :page [ページ番号] :cats [カテゴリごとの取得数計と総番組数からなるマップ]}
   :wait  1秒待機する。したがって回数＝秒数である。
          {:cmd :wait, :sec [残り待機回数], :total [全体待機回数]})"
  [oc-ui oc-db]
  (let [WAITING-INTERVAL-SEC 90 ; RSS取得サイクル一巡したらこの秒数だけ間隔あけて再開する
        FETCH-INTERVAL-MSEC 100 ; RSS取得リクエストは最低このミリ秒数だけあけて行う
        FETCH-OFFICIAL-INTERVAL-MSEC 600000 ; 公式放送のRSSはそれほど頻繁にチェックする必要はないので間隔をあける
        cc (ca/chan)  ; control channel
        wc (ca/chan)] ; worker channel

    (ca/go-loop [curr-cats nil ; ユーザー生放送カテゴリーごとの取得状況
                 last-fetched 0]
      (if-let [c (ca/<! wc)]
        (condp = (:cmd c)
          :fetch (let [page (:page c)
                       rest-interval (- (+ FETCH-INTERVAL-MSEC last-fetched) (System/currentTimeMillis))
                       [report cats]
                       (try
                         (when (pos? rest-interval) (Thread/sleep rest-interval))
                         (let [{:keys [page result cats npgms cmd-db cmd-ui]}
                               (condp = page
                                 0 (fetch)
                                 1 (fetch page nil)
                                 (fetch page curr-cats))]
                           (when cmd-db
                             (if (list? cmd-db)
                               (doseq [c cmd-db] (ca/>! oc-db c))
                               (ca/>! oc-db cmd-db)))

                           (when cmd-ui
                             (ca/>! oc-ui cmd-ui))
                           [{:cmd :fetching-report :result result :page page :npgms npgms} cats])
                         (catch Exception e
                           (log/warnf "failed fetching RSS(%d) %s" page (.getMessage e))
                           [{:cmd :fetching-report :result :error :page page :npgms 0} nil]))]
                   (ca/>! cc report)
                   (recur (or cats curr-cats) (System/currentTimeMillis)))
          :wait  (let [{:keys [sec total]} c]
                   (ca/>! oc-ui {:status :waiting-rss :sec sec :total total})
                   (Thread/sleep 1000)
                   (ca/>! cc {:cmd :waiting-report :sec sec :total total :result true})
                   (recur nil 0))
          (do
            (log/warnf "Caught an unknown command: (%s)" (pr-str c))
            (recur curr-cats last-fetched)))
        (log/infof "Closed RSS worker channel")))

    (ca/go-loop [mode false
                 last-official-fetched 0] ; 最後に公式放送のRSSを取得した時刻
      (if-let [c (ca/<! cc)] ; ccは外からとwcからの両方がありえる
        (condp = (:cmd c)
          ;; UIからの開始/終了指示処理
          :act (if mode
                 (do
                   (log/info "Stop RSS")
                   (recur false last-official-fetched))
                 (let [now (System/currentTimeMillis)
                       page (if (> now (+ last-official-fetched FETCH-OFFICIAL-INTERVAL-MSEC)) 0 1)]
                   (log/info "Start RSS")
                   (ca/>! oc-ui {:status :started-rss})
                   (ca/>! wc {:cmd :fetch :page page})
                   (recur true (if (zero? page) now last-official-fetched))))

          ;; ここからはwcからの報告処理
          :fetching-report
          (let [{:keys [result page npgms]} c]
            (log/trace (pr-str c))
            (if mode
              (condp = result
                :success (if (and (zero? npgms) (pos? page))
                           (do
                             (ca/>! wc {:cmd :wait :sec WAITING-INTERVAL-SEC :total WAITING-INTERVAL-SEC})
                             (recur mode last-official-fetched))
                           (do
                             (ca/>! wc {:cmd :fetch :page (inc page)})
                             (recur mode (if (zero? page) (System/currentTimeMillis) last-official-fetched))))
                :error   (do ; 停止
                           (log/infof "STOP RSS (%d) caused by fetching error" page)
                           (ca/>! oc-ui {:status :stopped-rss :reason "stopped by network error"})
                           (recur false last-official-fetched)))
              (do
                (ca/>! oc-ui {:status :stopped-rss})
                (recur mode last-official-fetched))))

          :waiting-report
          (let [{:keys [sec total result]} c]
            (if mode
              (if (= 1 sec)
                (let [page (if (> (System/currentTimeMillis) (+ last-official-fetched FETCH-OFFICIAL-INTERVAL-MSEC))
                             0 1)]
                  (ca/>! wc {:cmd :fetch :page page})
                  (recur mode last-official-fetched))
                (do
                  (ca/>! wc {:cmd :wait :sec (dec sec) :total total})
                  (recur mode last-official-fetched)))
              (do
                (ca/>! oc-ui {:status :stopped-rss})
                (recur mode last-official-fetched))))

          (do
            (log/warnf "Caught an unknown command: (%s)" (pr-str c))
            (recur mode last-official-fetched)))

        (do
          (ca/close! wc)
          (log/infof "Closed RSS control channel"))))

    cc))
