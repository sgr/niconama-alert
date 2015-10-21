;; -*- coding: utf-8-unix -*-
(ns nico.scrape
  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [net.cgrand.enlive-html :as html]
            [nico.net :as net]
            [nico.pgm :as pgm]
            [nico.string :as s])
  (:import [java.io InputStream StringReader]
           [java.net URI]
           [java.text SimpleDateFormat]
           [java.util Calendar GregorianCalendar Locale TimeZone]
           [java.util.concurrent TimeUnit]
           [org.htmlcleaner CompactXmlSerializer HtmlCleaner TagNode]))

(def ^{:private true} BASE-URL "http://live.nicovideo.jp/watch/")
(def ^{:private true} COUNT-URL "http://live.nicovideo.jp/timetable")
(def ^{:private true} INTERVAL-RETRY 5)
(def ^{:private true} LIMIT-RETRY 5)

(let [cleaner (let [c (HtmlCleaner.)]
                  (doto (.getProperties c)
                    (.setOmitComments true)
                    (.setPruneTags "style"))
                  c)
      serializer (CompactXmlSerializer. (.getProperties cleaner))
      CS "utf-8"]
  (defn- clean [^InputStream is]
    (.clean cleaner is CS))

  (defn- serialize [tag-node]
    (.getAsString serializer tag-node CS)))

(defn- last-path-uri-str [s-uri]
  (when-not (cs/blank? s-uri)
    (try
      (-> (URI. s-uri) .getPath (cs/split #"/") last)
      (catch Exception e (log/warnf "Malformed URI string %s" s-uri)))))

(defn- extract-start-time [open_time ^String start]
  (let [sc (doto (GregorianCalendar.) (.setTimeInMillis open_time))
        [shh smm] (if start (map #(Integer/parseInt %) (.split start ":")) [nil nil])]
    (when (and (= 23 (.get sc Calendar/HOUR_OF_DAY)) (= 0 shh)) ;; 開場23:5x、開演00:0xの場合
      (.add sc Calendar/DAY_OF_MONTH 1))
    (doto sc
      (.set Calendar/HOUR_OF_DAY shh)
      (.set Calendar/MINUTE smm))
    (.getTimeInMillis sc)))

(defn extract-pgm [xml]
  (if-let [node (some #(re-find #"\*短時間での連続アクセス\*" %) (html/texts xml))]
    (log/warnf "frequently access error: %s" (pr-str node))
    (let [link (-> xml (html/select [(html/attr= :property "og:url")]) first :attrs :content s/nstr)
          id (-> link last-path-uri-str s/nstr)
          title (-> xml (html/select [(html/attr= :property "og:title")]) first :attrs :content s/nstr)
          description (-> xml (html/select [(html/attr= :property "og:description")]) first :attrs :content s/del-dup s/nstr)
          thumbnail (-> xml (html/select [(html/attr= :property "og:image")]) first :attrs :content s/nstr)
          type (let [last-script (-> xml (html/select [:div.container :script]) last :content first)
                     type-str (->> last-script (re-find #"(?i)provider_type: \"(.*)\"") second)]
                 (-> type-str cs/lower-case keyword))
          open_time (when-let [s (-> xml (html/select [(html/attr= :itemprop "datePublished")]) first :attrs :content)]
                      (.. (SimpleDateFormat. "yyyy-MM-dd'T'HH:mmZ") (parse s) getTime))
          start_time (when-let [start (if (contains? #{:channel :official} type)
                                        (-> xml (html/select [:div.kaijo :> :strong :> html/text-node]) last)
                                        (-> xml (html/select [:div.time :> :span :> html/text-node]) last))]
                       (extract-start-time open_time start))
          member_only (if (empty? (html/select xml [:span.community-only])) 0 1)
          category (->> (html/select xml [:div#livetags :nobr :a.nicopedia html/text-node])
                        (cs/join ",")
                        s/nstr)
          comm_id (condp = type
                    :community (-> xml (html/select [:div.com :div.shosai :a]) second :attrs :href last-path-uri-str s/nstr)
                    :channel (-> xml (html/select [:div.chan :div.shosai :a]) first :attrs :href last-path-uri-str s/nstr)
                    :official nil)
          comm_name (condp = type
                      :community (-> xml (html/select [:div.com (html/attr= :itemprop "name" ) :> html/text-node]) first (s/unescape :html) s/nstr)
                      :channel (-> xml (html/select [:div.chan :div.shosai :a :> html/text-node]) first (s/unescape :html) s/nstr)
                      :official nil)
          owner_name (condp = type
                       :community (-> xml (html/select [:div.com :strong.nicopedia_nushi (html/attr= :itemprop "member") :> html/text-node])
                                      first (s/unescape :html) s/nstr)
                       :channel (-> xml (html/select [:div.chan :div.shosai (html/attr= :itemprop "name") :> html/text-node])
                                    first (s/unescape :html) s/nstr)
                       :official nil)
          now (System/currentTimeMillis)]
      (if (and (not-every? cs/blank? [id title link thumbnail]) description open_time start_time)
        (pgm/->Pgm id title open_time start_time description category link thumbnail owner_name
                   member_only ({:community 0 :channel 1 :official 2} type) comm_name comm_id now now)
        (log/warnf "couldn't create pgm from scraped data: [%s %s %s %s %s, %s %s]"
                   id title description link thumbnail open_time start_time)))))

(defn- fetch-pgm
  "ニコ生の番組ページから番組情報を取得する。"
  [pid cid]
  (letfn [(fetch-pgm-aux [pid cid]
            (try
              (with-open [is (-> (str BASE-URL pid) (net/http-get {:as :stream}) :body)
                          rdr (-> is clean serialize (StringReader.))]
                (extract-pgm (html/xml-resource rdr)))
              (catch Exception e
                (log/warnf e "failed fetching pgm info: %s, %s" pid (.getMessage e)))))]
    (loop [cnt 0]
      (if (< cnt LIMIT-RETRY)
        (if-let [pgm (fetch-pgm-aux pid cid)]
          pgm
          (do
            (log/warnf "retrying fetching pgm indo: %s" pid)
            (.sleep TimeUnit/SECONDS INTERVAL-RETRY)
            (recur (inc cnt))))
        (log/warnf "reached retry limit. aborted fetching pgm info: %s" pid)))))

(defn- create-pgm-from-scrapedinfo [pid cid] (fetch-pgm pid cid))

(def ^{:private true} LIMIT-ELAPSED 1200000) ;; APIによる番組ID取得からこの秒以上経過したら情報取得を諦める。

(defn scrape-pgm [pid cid received]
  (let [now (System/currentTimeMillis)]
    ;; 繁忙期は番組ページ閲覧すら重い。番組ID受信から LIMIT-ELAPSED 秒経過していたら諦める。
    (if (> LIMIT-ELAPSED (- now received))
      (if-let [pgm (create-pgm-from-scrapedinfo pid cid)]
        pgm
        (log/warnf "failed fetching pgm: %s/%s" pid cid))
      (log/warnf "too late to fetch: %s/%s" pid cid))))

(defn scrape-total
  "ニコ生の総番組数を取得する。"
  []
  (try
    (with-open [is (-> COUNT-URL (net/http-get {:as :stream}) :body)
                rdr (-> is clean serialize (StringReader.))]
      (when-let [cnt (-> (html/xml-resource rdr)
                         (html/select [:div.score_search :strong.count :> html/text-node])
                         first)]
        (Integer/parseInt cnt)))
    (catch Exception e
      (log/warnf "failed scraping total count: %s" (.getMessage e)))))
