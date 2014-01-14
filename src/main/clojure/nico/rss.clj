;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコニコ生放送配信中の放送情報RSSより、放送情報を取得する。
             RSSは文字数制限があるようで、タイトルや説明が切れることがある。
             また、絵文字の類いがそのまま？RSSに混入するようなので、まずいものは除去する。"}
  nico.rss
  (:use [clojure.tools.logging])
  (:require [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.data.zip :as dz]
            [clojure.data.zip.xml :as dzx]
            [nico.pgm :as pgm]
            [nico.api-updator :as api]
            [nico.log :as l]
            [net-utils :as n]
            [str-utils :as s]
            [time-utils :as tu])
  (:import [java.text SimpleDateFormat]
           [java.util Locale]
           [java.util.concurrent TimeUnit]))

(def ^{:private true} RETRY 0)
(def ^{:private true} WAIT 5)

(defn- get-nico-rss-aux [page]
  (try
    (n/with-http-res [raw-res (n/http-get (format "http://live.nicovideo.jp/recent/rss?p=%s" page))]
      (try
        (-> raw-res :body s/cleanup s/utf8stream xml/parse)
        (catch Exception e
          (error e (format "failed parsing RSS #%d, raw-response:\n%s" page (pr-str raw-res)))
          nil)))
    (catch Exception e
      (error e (format "failed fetching RSS #%d" page))
      nil)))

(defn- get-nico-rss [page]
  (loop [c RETRY]
    (if-let [rss (get-nico-rss-aux page)]
      (l/with-trace (format "fetched RSS #%d tried %d times." page (- RETRY c))
        rss)
      (if (= 0 c)
        (l/with-error (format "aborted fetching RSS #%d: reached limit." page)
          {})
        (do (.sleep TimeUnit/SECONDS WAIT)
            (recur (dec c)))))))

(defn- get-programs-count
  "get the total programs count."
  ([] (get-programs-count (get-nico-rss 1)))
  ([rss] (if-let [total-count (first (dzx/xml-> (zip/xml-zip rss) :channel :nicolive:total_count dzx/text))]
           (try
             (Integer/parseInt total-count)
             (catch NumberFormatException e
               (error e (format "failed fetching RSS for get programs count: %s" rss))
               0))
           (do (warn (format "RSS parse error: %s" rss))
               0))))

(defn- get-child-elm [tag node]
  (some #(if (= tag (:tag %)) %) (:content node)))

(defn- get-child-content [tag node]
  (s/unescape (first (:content (get-child-elm tag node))) :xml))

(defn- get-child-attr [tag attr node]
  (attr (:attrs (get-child-elm tag node))))

(let [fmt (SimpleDateFormat. "yyyy-MM-dd HH:mm:ss")]
  (defn- create-official-pgm [item fetched_at]
    (let [id (keyword (get-child-content :guid item))
          title (get-child-content :title item)
          pubdate (if-let [start-time-str (get-child-content :nicolive:start_time item)]
                    (try
                      (locking fmt
                        (.parse fmt start-time-str))
                      (catch Exception e
                        (error e (format "failed parsing str as date: %s" start-time-str))))
                    (trace "start_time is nil"))
          desc (if-let [s (get-child-content :description item)] (s/remove-tag s) "")
          ;; category (get-child-content :category item) ; ない
          link (get-child-content :link item)
          thumbnail (get-child-attr :media:thumbnail :url item)
          ;; owner_name (get-child-content :nicolive:owner_name item) ; ない
          ;; member_only (Boolean/parseBoolean (get-child-content :nicolive:member_only item)) ; ない
          ;; type (if-let [type-str (get-child-content :nicolive:type item)] ; Officialのみ
          ;;        (condp = type-str
          ;;          "community" :community
          ;;          "channel" :channel
          ;;          :official)
          ;;        :official)
          ;; comm_name (get-child-content :nicolive:community_name item) ; ない
          ;; comm_id (keyword (get-child-content :nicolive:community_id item)) ; ない
          ]
      (when (and id title pubdate desc link thumbnail)
        (nico.pgm.Pgm. id title pubdate desc nil link thumbnail nil false :official nil nil false fetched_at fetched_at)))))

(let [fmt (SimpleDateFormat. "EEE, dd MMM yyyy HH:mm:ss Z" Locale/ENGLISH)]
  (defn- create-pgm [item fetched_at]
    (let [id (keyword (get-child-content :guid item))
          title (get-child-content :title item)
          pubdate (if-let [pubdate-str (get-child-content :pubDate item)]
                    (try
                      (locking fmt
                        (.parse fmt pubdate-str))
                      (catch Exception e
                        (error e (format "failed parsing str as date: %s" pubdate-str))))
                    (trace "pubdate is nil"))
          desc (if-let [s (get-child-content :description item)] (s/remove-tag s) "")
          category (get-child-content :category item)
          link (get-child-content :link item)
          thumbnail (get-child-attr :media:thumbnail :url item)
          ;;   (first (clojure.string/split (get-child-attr :media:thumbnail :url item) #"\?"))
          owner_name (get-child-content :nicolive:owner_name item)
          member_only (Boolean/parseBoolean (get-child-content :nicolive:member_only item))
          type (if-let [type-str (get-child-content :nicolive:type item)]
                 (condp = type-str
                   "community" :community
                   "channel" :channel
                   :official)
                 :official)
          comm_name (get-child-content :nicolive:community_name item)
          comm_id (keyword (get-child-content :nicolive:community_id item))]
      (when (and id title pubdate desc link thumbnail)
        (nico.pgm.Pgm. id title pubdate desc category link thumbnail owner_name false type comm_name comm_id false fetched_at fetched_at)))))

(defn- get-programs-from-rss-aux [rss]
  [(get-programs-count rss)
   (let [nodes-child (dzx/xml-> (zip/xml-zip rss) :channel dz/children)
         items (for [x nodes-child :when (= :item (:tag (first x)))] (first x))
         now (tu/now)]
     (if (< 0 (count items))
       (remove
        nil?
        (for [item items]
          (when-let [pgm (create-pgm item now)]
            (if (some nil? (list (:id pgm) (:title pgm) (:pubdate pgm)))
              (l/with-trace (format "Some nil properties found in: %s" (pr-str item))
                (if (and (:id pgm) (:comm_id pgm) (nil? (pgm/get-pgm (:id pgm))))
                  (api/request-fetch (name (:id pgm)) (name (:comm_id pgm)) now)
                  (trace (format "abondoned fetching pgm for lack of ids or fetched already: %s" (pr-str pgm)))))
              pgm))))
       (trace (format "no items in rss: %s" (pr-str nodes-child)))))])

(defn get-programs-from-rss [page]
  (loop [c RETRY
         rss (get-nico-rss page)
         [total pgms] (get-programs-from-rss-aux rss)]
    (if (= 0 c)
      [total (or pgms '())]
      (if (or (>= 0 total) (nil? pgms))
        (l/with-debug (format "retry fetching RSS #%d (%d) total number: %d, pgms: %d"
                              page c total (if-not pgms 0 (count pgms)))
          (.sleep TimeUnit/SECONDS WAIT)
          (recur (dec c) (get-nico-rss page) (get-programs-from-rss-aux rss)))
        [total pgms]))))
