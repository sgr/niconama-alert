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
  (:import [java.util Locale]
           [java.util.concurrent TimeUnit]
           [org.apache.commons.lang3.time FastDateFormat]
           [org.htmlcleaner HtmlCleaner]))

(def ^{:private true} RETRY 2)

(defn- get-nico-rss-aux [url]
  (try
    (net/with-http-res [raw-res (net/http-get url)]
      (with-open [is (-> raw-res :body s/cleanup s/utf8stream)]
        (xml/parse is)))
    (catch Exception e
      (log/warnf "failed fetching RSS (%s)" url))))

(defn- get-nico-rss [url]
  (loop [rss (get-nico-rss-aux url), c RETRY wait 1]
    (if (or rss (zero? c))
      rss
      (do
        (.sleep TimeUnit/SECONDS wait)
        (recur (get-nico-rss-aux url) (dec c) (inc wait))))))

(defn- get-programs-count
  "get the total programs count."
  [rss]
  (when-let [total-count (first (dzx/xml-> (zip/xml-zip rss) :channel :nicolive:total_count dzx/text))]
    (try
      (Integer/parseInt total-count)
      (catch NumberFormatException e
        (log/errorf "failed fetching RSS for get programs count: %s" rss)))))

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

(defn- parse-date
  ([s fmt]
     (when-not (cs/blank? s)
       (-> (FastDateFormat/getInstance fmt) (.parse s) .getTime)))
  ([s fmt locale]
     (when-not (cs/blank? s)
       (-> (FastDateFormat/getInstance fmt locale) (.parse s) .getTime))))

(let [fmt "yyyy-MM-dd HH:mm:ss"]
  (defn create-official-pgm [item fetched_at]
    (try
      (let [id (-> item (child-content :guid) s/nstr)
            title (-> item (child-content :title) (s/unescape :xml) s/nstr)
            open_time (-> item (child-content :nicolive:open_time) (parse-date fmt))
            start_time (-> item (child-content :nicolive:start_time) (parse-date fmt))
            description (-> item (child-content :description) (s/unescape :xml) remove-tag s/nstr)
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

(let [fmt "EEE, dd MMM yyyy HH:mm:ss Z"
      locale Locale/ENGLISH]
  (defn create-pgm [item fetched_at]
    (try
      (let [id (-> item (child-content :guid) s/nstr)
            title (-> item (child-content :title) (s/unescape :xml) s/nstr)
            open_time (-> item (child-content :pubDate) (parse-date fmt locale))
            start_time open_time
            description (-> item (child-content :description) (s/unescape :xml) remove-tag s/nstr)
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
        (if (and (not-every? cs/blank? [id title link thumbnail]) description open_time start_time)
          (pgm/->Pgm id title open_time start_time description category link thumbnail owner_name
                     member_only type comm_name comm_id fetched_at fetched_at)
          (log/debugf "couldn't create pgm: [%s %s %s %s %s, %s %s]"
                      id title description link thumbnail open_time start_time)))
      (catch Exception e
        (log/warnf "failed creating pgm from RSS: %s" (-> item pr-str s/nstr))))))

(defn- items [rss]
  (let [nodes-child (dzx/xml-> (zip/xml-zip rss) :channel dz/children)]
    (for [x nodes-child :when (= :item (:tag (first x)))] (first x))))

(defn extract [rss pgm-fn]
  (let [now (System/currentTimeMillis)]
    (->> (items rss)
         (map #(pgm-fn % now))
         (filter #(and % (and (:id %) (:title %) (:open_time %) (:start_time %)))))))


(defn- get-programs-from-rss
  ([] ; ページなしは公式の番組取得。RSSフォーマットが異なる。
     (if-let [rss (get-nico-rss "http://live.nicovideo.jp/rss")]
       (extract rss create-official-pgm) []))
  ([page] ; pageは1以上。0で問い合わせると1と同じ結果が返るみたい。また、カテゴリ無しはcommonカテゴリと同じ結果が返るみたい。
     {:pre [(pos? page)]}
     (if-let [rss (get-nico-rss (format "http://live.nicovideo.jp/recent/rss?p=%d" page))]
       [(get-programs-count rss) (extract rss create-pgm)] [nil []]))
  ([page category] ; pageは1以上。0で問い合わせると1と同じ結果が返るみたい。
     {:pre [(pos? page)]}
     (if-let [rss (get-nico-rss (format "http://live.nicovideo.jp/recent/rss?tab=%s&p=%d" category page))]
       [(get-programs-count rss) (extract rss create-pgm)] [nil []])))

(defn boot
  "ニコ生RSSを通じて番組情報を取得するfetcherを生成し、コントロールチャネルを返す。
   引数oc-statusにはfetcherからの状態を受け取るチャネルを、
   引数oc-dbにはfetcherが得た番組情報を受け取るチャネルをそれぞれ指定する。

   アウトプットチャネルoc-statusには次のステータスが出力される。
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
          {:cmd :add-pgms :pgms [番組情報] :total [カテゴリごとの総番組数の合計。実際の総番組数より大きいが取得数を表す。]}
   :set-total 総番組数を設定する。
          {:cmd :set-total :total [公式・チャンネルの番組数 + ニコ生から得た総番組数] }
   :finish 今回のRSS取得サイクルが一巡したことを教える。
          {:cmd :finish :total [ニコ生から得た総番組数]}

   コントロールチャネルは次のコマンドを受理する。
   :act 今のfetcherの状態によって開始または終了するトグル動作。
          {:cmd :act}

   また、次のコマンドが内部的に使用される。
   :fetch 指定されたページのRSSを取得する。0ページと1ページ以降は異なる。
          {:cmd :fetch} ; 0ページ目は公式・チャンネルの番組
          {:cmd :fetch :page [ページ番号] :cats [カテゴリごとの取得数計と総番組数からなるマップ]}
   :wait  1秒待機する。したがって回数＝秒数である。
          {:cmd :wait, :sec [残り待機回数], :total [全体待機回数]})"
  [oc-status oc-db]
  (let [WAITING-INTERVAL 90
        cc (ca/chan)]

    (letfn [(fetch
              ([]
                 (ca/go
                   (let [pgms (get-programs-from-rss)
                         npgms (count pgms)
                         real-total (or (scrape/scrape-total) 0)]
                     (when (pos? npgms)
                       (ca/>! oc-db {:cmd :set-total :total (+ npgms real-total)})
                       (ca/>! oc-db {:cmd :add-pgms :pgms pgms :total nil})
                       (ca/>! oc-status {:status :fetching-rss :page 0 :acc npgms :total nil}))
                     {:cmd :fetch :page 1 :cats {:common [0 0] ; 一般
                                                 :try    [0 0] ; やってみた
                                                 :live   [0 0] ; ゲーム
                                                 :req    [0 0] ; 動画紹介
                                                 :r18    [0 0] ; R-18
                                                 :face   [0 0] ; 顔出し
                                                 :totu   [0 0]}}))) ; 凸待ち
              ([page cats]
                 (ca/go
                   (let [[ncats pgms] (reduce (fn [[m pgms] [category [acc total]]]
                                                (if (or (= 1 page) (< acc total))
                                                  (let [[ctotal cpgms] (get-programs-from-rss page (name category))]
                                                    [(assoc m category [(+ acc (count cpgms))
                                                                        (if ((every-pred number? pos?) ctotal) ctotal total)])
                                                     (concat pgms cpgms)]
                                                    [(assoc m category [acc total]) pgms])))
                                              [{} []] cats)
                         npgms (count pgms)
                         sacc (->> ncats vals (map first) (apply +))
                         stotal (->> ncats vals (map second) (apply +))]
                     (when (pos? npgms)
                       (ca/>! oc-db {:cmd :add-pgms :pgms pgms})
                       (ca/>! oc-status {:status :fetching-rss :page page :acc sacc :total stotal}))
                     (if (pos? npgms)
                       {:cmd :fetch :page (inc page) :cats ncats}
                       {:cmd :wait :sec WAITING-INTERVAL :total WAITING-INTERVAL})))))
            (wait [sec total]
              (ca/go
                (ca/>! oc-status {:status :waiting-rss :sec sec :total total})
                (if (zero? sec)
                  {:cmd :fetch}
                  (do
                    (ca/<! (ca/timeout 1000))
                    {:cmd :wait :sec (dec sec) :total total}))))]

      (ca/go-loop [curr-op nil
                   abort false]
        (let [[c ch] (ca/alts! (->> [cc curr-op] (remove nil?)))]
          (if c
            (condp = (:cmd c)
              :act (if curr-op
                     (do
                       (log/info "Stop RSS")
                       (recur curr-op true))
                     (do
                       (log/info "Start RSS")
                       (ca/>! oc-status {:status :started-rss})
                       (recur (fetch) false)))
              :fetch (let [{:keys [page cats]} c]
                       (when curr-op (ca/close! curr-op))
                       (if abort
                         (do
                           (ca/>! oc-status {:status :stopped-rss})
                           (recur nil false))
                         (if (and page cats)
                           (recur (fetch page cats) false)
                           (recur (fetch) false))))
              :wait  (let [{:keys [sec total]} c]
                       (when curr-op (ca/close! curr-op))
                       (if abort
                         (do
                           (ca/>! oc-status {:status :stopped-rss})
                           (recur nil false))
                         (recur (wait sec total) false)))
              (do
                (log/warn "Unknown command " (pr-str c))
                (recur curr-op abort)))

            (cond ;; cがnilの場合
             (not= ch cc) (do
                            (log/warn "other channel closed: " (pr-str ch))
                            (ca/close! ch)
                            (ca/>! oc-status {:status :stopped-rss})
                            (recur nil abort))
             :else (log/infof "Closed RSS control channel"))))))

       cc))

