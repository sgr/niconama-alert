;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコニコ生放送配信中の放送情報RSSより、放送情報を取得する。
             RSSは文字数制限があるようで、タイトルや説明が切れることがある。
             また、絵文字の類いがそのまま？RSSに混入するようなので、まずいものは除去する。"}
    nico.rss
  (:require [clojure.xml :as xml]
	    [clojure.zip :as zip]
	    [clojure.contrib.zip-filter :as zf]
	    [clojure.contrib.zip-filter.xml :as zfx]
	    [clojure.contrib.duck-streams :as ds]
	    [nico.pgm :as pgm]
	    [log-utils :as lu]
	    [str-utils :as s]
	    [time-utils :as tu])
  (:import (java.text SimpleDateFormat)
	   (java.util Locale)))

;; タイムアウト値を設定。これ、SunのJREでないときはどうしたらよいだろうか？
(System/setProperty "sun.net.client.defaultConnectTimeout" "10000")
(System/setProperty "sun.net.client.defaultReadTimeout" "10000")

(defn get-nico-rss
  [page]
  (try
    (let [s (ds/slurp* (format "http://live.nicovideo.jp/recent/rss?p=%s" page))
	  cs (s/cleanup s)]
      (xml/parse (s/utf8stream cs)))
    (catch Exception e (do (lu/printe "failed fetching RSS" e) {}))))

(defn get-programs-count
  "get the total programs count."
  ([] (get-programs-count (get-nico-rss 1)))
  ([rss] (try
	   (Integer/parseInt
	    (first (zfx/xml-> (zip/xml-zip rss) :channel :nicolive:total_count zfx/text)))
	   (catch NumberFormatException e (do (lu/printe "failed fetching RSS" e) 0)))))

(defn- get-child-elm [tag node]
  (some #(if (= tag (:tag %)) %) (:content node)))

(defn- get-child-content [tag node]
  (first (:content (get-child-elm tag node))))

(defn- get-child-attr [tag attr node]
  (attr (:attrs (get-child-elm tag node))))

(defn- create-pgm [item fetched_at]
  (nico.pgm.Pgm.
   (keyword (get-child-content :guid item))
   (get-child-content :title item)
   (.parse (SimpleDateFormat. "EEE, dd MMM yyyy HH:mm:ss Z" Locale/ENGLISH)
	   (get-child-content :pubDate item))
   (get-child-content :description item)
   (get-child-content :category item)
   (get-child-content :link item)
   (get-child-attr :media:thumbnail :url item)
   (get-child-content :nicolive:owner_name item)
   (Boolean/parseBoolean (get-child-content :nicolive:member_only item))
   (if-let [type-str (get-child-content :nicolive:type item)]
     (condp = type-str
	 "community" :community
	 "channel" :channel
	 :official)
     :official)
   (get-child-content :nicolive:community_name item)
   (keyword (get-child-content :nicolive:community_id item))
   false
   fetched_at
   fetched_at))

(defn get-programs-from-rss-page [rss]
  (for [item (for [x (zfx/xml-> (zip/xml-zip rss) :channel zf/children)
		   :when (= :item (:tag (first x)))] (first x))]
    (let [pgm (create-pgm item (tu/now))]
      (when (some nil?
		  (list (:id pgm) (:pubdate pgm)))
	(println
	 (format " *** NULL-PGM-FROM-RSS: %s %s (%s) [%s-%s]"
		 (:id pgm) (:title pgm) (:link pgm)
		 (:pubdate pgm) (:fetched_at pgm))))
      pgm)))

