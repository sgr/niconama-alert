;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコ生のページから情報を取得する。"}
    nico.scrape
  (:use [clojure.tools.logging])
  (:require [net.cgrand.enlive-html :as html]
            [log-utils :as l]
	    [net-utils :as n]
	    [str-utils :as s]
	    [time-utils :as tu]
	    [clojure.string :as cs])
  (:import [java.io InputStream]
           [java.util Calendar Date GregorianCalendar Locale TimeZone]
           [java.util.concurrent TimeUnit]
           [org.htmlcleaner CompactXmlSerializer HtmlCleaner TagNode]))

(def ^{:private true} BASE-URL "http://live.nicovideo.jp/watch/")
(def ^{:private true} INTERVAL-RETRY 5)
(def ^{:private true} LIMIT-RETRY 5)

(defn- fetch-pgm-info1
  [pid]
  (let [url (str BASE-URL pid)
        cleaner (let [c (HtmlCleaner.)]
                  (doto (.getProperties c) (.setOmitComments true) (.setPruneTags "script, style"))
                  c)
	h (html/xml-resource (java.io.StringReader.
                              (.getAsString (CompactXmlSerializer. (.getProperties cleaner))
                                            ^TagNode (.clean cleaner ^InputStream (n/url-stream url) "utf-8")
                                            "utf-8")))
        base (-> (html/select h [:base]) first :attrs :href)
        infobox (first (html/select h [:div.infobox]))
        title (s/unescape (first (html/select infobox [:h2 (html/attr= :itemprop "name") :> html/text-node])) :html)
        desc (if-let [str (cs/join " " (for [n (html/select infobox [:div.bgm :> :div :> html/text-node])]
                                         (s/unescape n :html)))]
               (s/remove-tag str) "")
        pubdate (let [[^String sday ^String sopen ^String sstart] ; 開始日 開場時刻 開演時刻
                      (html/select infobox
                                   [:div.kaijo :> :strong :> html/text-node])]
                  (let [[yyyy MM dd] (for [x (rest (re-find #"(\d{4})/(\d{2})/(\d{2})" sday))]
                                       (Integer/parseInt x))
                        [ohh omm] (if sopen (for [x (.split sopen ":")] (Integer/parseInt x)) [nil nil])
                        [shh smm] (if sstart (for [x (.split sstart ":")] (Integer/parseInt x)) [nil nil])
                        ;; 終了後の番組ページを開く場合は開演時刻しか表示されないため、チェック。
                        c (doto (if (and shh smm)
                                  (GregorianCalendar. yyyy (dec MM) dd shh smm)
                                  (GregorianCalendar. yyyy (dec MM) dd ohh omm))
                            (.setTimeZone (TimeZone/getTimeZone "Asia/Tokyo")))]
                    (do
                      ;; 開場23:5x、開演00:0xの場合に対応
                      (when (and (= 23 ohh) (= 0 shh)) (.add c Calendar/DAY_OF_MONTH 1))
                      (.getTime c))))
        member_only (if (first (html/select infobox [:h2.onlym])) true false)
        type (cond
              (not (empty? (html/select infobox [:div.com]))) :community
              (not (empty? (html/select infobox [:div.chan]))) :channel
              :else :official)
        category (cs/join " " (for [x (html/select h [[:nobr (html/has [:img])]])]
                                (first (html/select x [:a.nicopedia :> html/text-node]))))
        comm (first (html/select infobox (condp = type
                                           :community [:div.com]
                                           :channel [:div.chan]
                                           [:div.official]))) ; こんなクラスはないから何も取れない
        comm_name (if comm
                    (condp = type
                      :community (let [name (html/select comm [:div.shosai (html/attr= :itemprop "name") :> html/text-node])]
                                   (s/unescape (first name) :html))
                      :channel   (let [name (html/select comm [:div.shosai :> :a :> html/text-node])]
                                   (s/unescape (first name) :html))
                      nil)
                    nil)
        owner_name (condp = type
                     :community (s/unescape
                                 (first
                                  (html/select
                                   comm [:strong.nicopedia_nushi (html/attr= :itemprop "member") :> html/text-node]))
                                 :html)
                     :channel (s/unescape
                               (second
                                (html/select comm [:div.shosai (html/attr= :itemprop "name") :> html/text-node]))
                               :html)
                     nil)
        thumbnail (let [bn (-> (html/select infobox [:div.bn :img]) first :attrs :src)]
;;                               (clojure.string/split #"\?") first)]
                    (if (= type :community) bn (str base bn)))
        now (tu/now)]
    (if-let [node (some #(re-find #"\*短時間での連続アクセス\*" %) (html/texts h))]
      (do
        (warn (format "frequently access error: %s" (pr-str node)))
        nil)
      (do
        (when-not pubdate (error (format " NULL PUBDATE: %s (%s)" title url)))
        {:title title
         :pubdate pubdate
         :desc desc
         :category category
         :link url
         :thumbnail thumbnail
         :owner_name owner_name
         :member_only member_only
         :type type
         :comm_name comm_name
         :fetched_at now
         :updated_at now}))))

(defn fetch-pgm-info
  "ニコ生の番組ページから番組情報を取得する。"
  [pid]
  (letfn [(fetch-pgm-info-aux [pid]
            (try
              (fetch-pgm-info1 pid)
              (catch Exception e
                (warn e (format "failed fetching pgm info: %s" pid))
                nil)))]
    (loop [cnt 0]
      (if (< cnt LIMIT-RETRY)
        (if-let [info (fetch-pgm-info-aux pid)]
          info
          (l/with-warn (format "retrying fetching pgm indo: %s" pid)
            (.sleep TimeUnit/SECONDS INTERVAL-RETRY)
            (recur (inc cnt))))
        (l/with-warn (format "reached retry limit. aborted fetching pgm info: %s" pid)
          nil)))))
