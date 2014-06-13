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
            [nico.string :as s])
  (:import [java.text SimpleDateFormat]
           [java.util Date Locale]
           [java.util.concurrent TimeUnit]
           [org.htmlcleaner HtmlCleaner]))

(def ^{:private true} RETRY 0)
(def ^{:private true} WAIT 5)

(defn- get-nico-rss-aux [url]
  (try
    (net/with-http-res [raw-res (net/http-get url)]
      (with-open [is (-> raw-res :body s/cleanup s/utf8stream)]
        (xml/parse is)))
    (catch Exception e
      (log/warnf "failed fetching RSS (%s)" url))))

(defn- get-nico-rss [url]
  (loop [rss (get-nico-rss-aux url), c RETRY]
    (if (or rss (zero? c))
      rss
      (do
        (.sleep TimeUnit/SECONDS WAIT)
        (recur (get-nico-rss-aux url) (dec c))))))

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
      (locking cleaner
        (->> (.clean cleaner s)
             (#(.getAllElements % true))
             (map #(.. % getText toString))
             cs/join)))))

(defn- child-elements [node tag]
  (->> (:content node) (filter #(= tag (:tag %)))))

(defn- child-content [node tag]
  (-> node (child-elements tag) first :content first))

(defn- get-child-attr [node tag attr]
  (-> node (child-elements tag) first :attrs attr))

(defn- parse-date [s fmt]
  (when-not (cs/blank? s)
    (locking fmt
      (.parse fmt s))))

(let [fmt (SimpleDateFormat. "yyyy-MM-dd HH:mm:ss")]
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
            member_only false ; ない
            type (if-let [type-str (child-content item :nicolive:type)] (keyword type-str) :official)
            comm_name "" ; ない
            comm_id nil] ; ない
        (if (and (not-every? cs/blank? [id title link thumbnail]) description open_time start_time)
          (nico.pgm.Pgm. id title open_time start_time description category link thumbnail owner_name
                         member_only type comm_name comm_id fetched_at fetched_at)
          (log/warnf "couldn't create official pgm: [%s %s %s %s %s, %s %s]"
                     id title description link thumbnail open_time start_time)))
      (catch Exception e
        (log/warnf "failed creating pgm from official RSS: %s" (-> item pr-str s/nstr))))))

(let [fmt (SimpleDateFormat. "EEE, dd MMM yyyy HH:mm:ss Z" Locale/ENGLISH)]
  (defn create-pgm [item fetched_at]
    (try
      (let [id (-> item (child-content :guid) s/nstr)
            title (-> item (child-content :title) (s/unescape :xml) s/nstr)
            open_time (-> item (child-content :pubDate) (parse-date fmt))
            start_time open_time
            description (-> item (child-content :description) (s/unescape :xml) remove-tag s/nstr)
            category (->> (child-elements item :category)
                          (map #(-> % :content first))
                          (cs/join ",")
                          s/nstr)
            link (-> item (child-content :link) s/nstr)
            thumbnail (-> item (get-child-attr :media:thumbnail :url) s/nstr)
            owner_name (-> item (child-content :nicolive:owner_name) s/nstr)
            member_only (-> item (child-content :nicolive:member_only) Boolean/parseBoolean)
            type (if-let [type-str (child-content item :nicolive:type)] (keyword type-str) :official)
            comm_name (-> item (child-content :nicolive:community_name) s/nstr)
            comm_id (-> item (child-content :nicolive:community_id) s/nstr)]
        (if (and (not-every? cs/blank? [id title link thumbnail]) description open_time start_time)
          (nico.pgm.Pgm. id title open_time start_time description category link thumbnail owner_name
                         member_only type comm_name comm_id fetched_at fetched_at)
          (log/debugf "couldn't create pgm: [%s %s %s %s %s, %s %s]"
                      id title description link thumbnail open_time start_time)))
      (catch Exception e
        (log/warnf e "failed creating pgm from RSS: %s" (-> item pr-str s/nstr))))))

(defn- items [rss]
  (let [nodes-child (dzx/xml-> (zip/xml-zip rss) :channel dz/children)]
    (for [x nodes-child :when (= :item (:tag (first x)))] (first x))))

(defn extract [rss pgm-fn]
  (let [now (Date.)]
    (->> (map #(pgm-fn % now) (items rss))
         (filter #(and % (and (:id %) (:title %) (:open_time %) (:start_time %)))))))

(defn- get-programs-from-rss [page]
  (if (= page 0) ; page 0 は公式の番組取得。RSSフォーマットが異なる。
    (let [rss (get-nico-rss "http://live.nicovideo.jp/rss")]
      [nil (extract rss create-official-pgm)])
    (let [rss (get-nico-rss (format "http://live.nicovideo.jp/recent/rss?p=%d" page))]
      [(get-programs-count rss) (extract rss create-pgm)])))

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
          {:status :waiting-rss :rest [残り秒] :total [全体待機時間]}
   :fetching-rss RSS取得中。得られた番組情報つき。
          {:status :fetching-rss :page [取得したページ] :acc [今サイクルで取得した番組数計] :total [総番組数]}

   アウトプットチャネルoc-dbには番組情報が出力される。
   :add-pgms 番組情報追加。上の:fetching-rssと同時に発信される。
          {:cmd :add-pgms :pgms [番組情報] :total [総番組数]}

   コントロールチャネルは次のコマンドを受理する。
   :act 今のfetcherの状態によって開始または終了するトグル動作。
          {:cmd :act}

   また、次のコマンドが内部的に使用される。
   :fetch 指定されたページのRSSを取得する。
          {:cmd :fetch :page [ページ番号] :acc [今サイクルでこれまでに取得した番組数計]}
   :wait  1秒待機する。したがって回数＝秒数である。
          {:cmd :wait, :rest [残り待機回数], :total [全体待機回数]})"
  [oc-status oc-db]
  (let [WAITING-INTERVAL 90
        cc (ca/chan)]
    (letfn [(fetch [page acc]
              (ca/go
                (let [[total pgms] (get-programs-from-rss page)
                      acc (+ acc (count pgms))]
                  (ca/>! oc-db {:cmd :add-pgms :pgms pgms :total total})
                  (ca/>! oc-status {:status :fetching-rss :page page :acc acc :total total})
                  (if (and total (or (<= total acc) (= 0 (count pgms))))
                    (do
                      (ca/>! oc-db {:cmd :finish})
                      {:cmd :wait :rest WAITING-INTERVAL, :total WAITING-INTERVAL})
                    {:cmd :fetch :page (inc page) :acc acc}))))
            (wait [rest total]
              (ca/go
                (ca/>! oc-status {:status :waiting-rss :rest rest :total total})
                (if (= 0 rest)
                  {:cmd :fetch :page 0 :acc 0}
                  (do
                    (ca/<! (ca/timeout 1000))
                    {:cmd :wait :rest (dec rest) :total total}))))]

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
                       (recur (fetch 0 0) false)))
              :fetch (let [{:keys [page acc]} c]
                       (ca/close! ch)
                       (if abort
                         (do
                           (ca/>! oc-status {:status :stopped-rss})
                           (recur nil false))
                         (recur (fetch page acc) false)))
              :wait  (let [{:keys [rest total]} c]
                       (ca/close! ch)
                       (if abort
                         (do
                           (ca/>! oc-status {:status :stopped-rss})
                           (recur nil false))
                         (recur (wait rest total) false)))
              (do
                (log/warn "Unknown command " (pr-str c))
                (recur curr-op abort)))

            (cond ;; cがnilの場合
             (not= ch cc) (do
                            (log/warn "other channel closed: " (pr-str ch))
                            (ca/close! ch)
                            (recur nil abort))
             :else (log/infof "Closed RSS control channel"))))))
    cc))
