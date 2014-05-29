;; -*- coding: utf-8-unix -*-
(ns nico.scrape
  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [net.cgrand.enlive-html :as html]
            [nico.net :as net]
            [nico.string :as s])
  (:import [java.io InputStream StringReader]
           [java.net URI]
           [java.util Calendar Date GregorianCalendar Locale TimeZone]
           [java.util.concurrent TimeUnit]
           [org.htmlcleaner CompactXmlSerializer HtmlCleaner TagNode]))

(def ^{:private true} BASE-URL "http://live.nicovideo.jp/watch/")
(def ^{:private true} INTERVAL-RETRY 5)
(def ^{:private true} LIMIT-RETRY 5)

(let [cleaner (let [c (HtmlCleaner.)]
                  (doto (.getProperties c)
                    (.setOmitComments true)
                    (.setPruneTags "script, style"))
                  c)
      serializer (CompactXmlSerializer. (.getProperties cleaner))
      CS "utf-8"]
  (defn- clean [^InputStream is]
    (locking cleaner
      (.clean cleaner is CS)))

  (defn- serialize [tag-node]
    (locking serializer
      (.getAsString serializer tag-node CS))))

(defn- last-path-uri-str [s-uri]
  (when-not (cs/blank? s-uri)
    (try
      (-> (URI. s-uri) .getPath (cs/split #"/") last)
      (catch Exception e (log/warnf "Malformed URI string %s" s-uri)))))

(defn- open-start-time [[sday sopen sstart]]
  (let [[yyyy MM dd] (map #(Integer/parseInt %) (rest (re-find #"(\d{4})/(\d{2})/(\d{2})" sday)))
        [ohh omm] (if sopen (map #(Integer/parseInt %) (.split sopen ":")) [nil nil])
        [shh smm] (if sstart (map #(Integer/parseInt %) (.split sstart ":")) [nil nil])
        ;; 終了後の番組ページを開く場合は開演時刻しか表示されないため、sopenが開演時刻
        [oc sc] (map #(doto % (.setTimeZone (TimeZone/getTimeZone "Asia/Tokyo")))
                     (if sstart
                       [(GregorianCalendar. yyyy (dec MM) dd ohh omm)
                        (GregorianCalendar. yyyy (dec MM) dd shh smm)]
                       [(GregorianCalendar. yyyy (dec MM) dd ohh omm)
                        (GregorianCalendar. yyyy (dec MM) dd ohh omm)]))]
    (when (and (= 23 ohh) (= 0 shh)) ;; 開場23:5x、開演00:0xの場合
      (.add sc Calendar/DAY_OF_MONTH 1))
    [(.getTime oc) (.getTime sc)]))

(defn extract-pgm [xml]
  (if-let [node (some #(re-find #"\*短時間での連続アクセス\*" %) (html/texts xml))]
    (log/warnf "frequently access error: %s" (pr-str node))
    (let [link (-> (html/select xml [(html/attr= :property "og:url")]) first :attrs :content)
          id (last-path-uri-str link)
          base (-> (html/select xml [:base]) first :attrs :href)
          infobox (first (html/select xml [:div.infobox]))
          title (-> (html/select infobox [:h2 (html/attr= :itemprop "name") :> html/text-node])
                    first
                    (s/unescape :html))
          description (-> (html/select xml [(html/attr= :property "og:description")]) first :attrs :content)
          [open_time start_time] (-> (html/select infobox [:div.kaijo :> :strong :> html/text-node])
                                     open-start-time)
          thumbnail (-> (html/select xml [(html/attr= :itemprop "thumbnail")])
                        first :attrs :content)
          member_only (if (first (html/select infobox [:h2.onlym])) true false)
          type (cond
                (not (empty? (html/select infobox [:div.com]))) :community
                (not (empty? (html/select infobox [:div.chan]))) :channel
                :else :official)
          category (->> (html/select xml [:nobr :> :a.nicopedia :> html/text-node])
                        (cs/join ","))
          comm (-> (html/select infobox (condp = type
                                          :community [:div.com]
                                          :channel [:div.chan]
                                          [:div.official])) ; こんなクラスはないから何も取れない
                   first)
          comm_id (when-let [selector (condp = type
                                        :community [:a.community]
                                        :channel [:div.shosai :> :a])]
                    (-> (html/select xml selector) first :attrs :href last-path-uri-str))
          comm_name (when-let [selector (condp = type
                                          :community [:div.shosai (html/attr= :itemprop "name")
                                                      :> html/text-node]
                                          :channel [:div.shosai :> :a :> html/text-node])]
                      (-> (html/select comm selector) first (s/unescape :html)))
          owner_name (when-let [selector (condp = type
                                           :community [:strong.nicopedia_nushi
                                                       (html/attr= :itemprop "member")
                                                       :> html/text-node]
                                           :channel [:div.shosai (html/attr= :itemprop "name")
                                                     :> html/text-node])]
                       (-> (html/select comm selector) first (s/unescape :html)))
          now (Date.)]
      (if (and (not-every? cs/blank? [id title link thumbnail]) description open_time start_time)
        (nico.pgm.Pgm. id title open_time start_time description category link thumbnail owner_name
                       member_only type comm_name comm_id now now)
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
                (log/warnf e "failed fetching pgm info: %s" pid))))]
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

