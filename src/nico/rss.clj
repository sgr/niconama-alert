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
	    [clj-http.client :as client]
	    [nico.pgm :as pgm]
	    [net-utils :as n]
	    [str-utils :as s]
	    [time-utils :as tu])
  (:import [java.text SimpleDateFormat]
	   [java.util Locale]
           [java.util.concurrent TimeUnit]))

(def ^{:private true} RETRY 10)
(def ^{:private true} WAIT 3)

(defn get-nico-rss-aux
  [page]
  (try
    (let [raw-res (client/get (format "http://live.nicovideo.jp/recent/rss?p=%s" page))]
      (if (= 200 (:status raw-res))
        (-> raw-res :body s/cleanup s/utf8stream xml/parse)
        (let [msg (format "returned HTTP error: %d, %s" (:status raw-res) (:body raw-res))]
          (error msg)
          (throw (Exception. msg)))))
    (catch Exception e
      (error (format "failed fetching RSS #%d: %s" page (.getMessage e)) e) nil)))

(defn get-nico-rss
  [page]
  (loop [c RETRY]
    (if-let [rss (get-nico-rss-aux page)]
      (do (debug (format "fetched RSS #%d tried %d times." page (- RETRY c)))
          rss)
      (if (= 0 c)
        (do (error (format "aborted fetching RSS #%d: reached limit." page))
            {})
        (do (.sleep TimeUnit/SECONDS WAIT)
            (recur (dec c)))))))

(defn get-programs-count
  "get the total programs count."
  ([] (get-programs-count (get-nico-rss 1)))
  ([rss] (try
	   (Integer/parseInt
	    (first (dzx/xml-> (zip/xml-zip rss) :channel :nicolive:total_count dzx/text)))
	   (catch NumberFormatException e
	     (error (format "failed fetching RSS for get programs count: %s" rss) e)
	     0))))

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

(defn get-programs-from-rss-aux [rss]
  [(get-programs-count rss)
   (for [item (for [x (dzx/xml-> (zip/xml-zip rss) :channel dz/children)
		    :when (= :item (:tag (first x)))] (first x))]
     (let [pgm (create-pgm item (tu/now))]
       (when (some nil?
		   (list (:id pgm) (:title pgm) (:pubdate pgm)))
	 (warn (format "Some nil properties found in: %s" (pr-str pgm))))
       pgm))])

(defn get-programs-from-rss [page]
  (loop [c RETRY
         rss (get-nico-rss page)
         [total pgms] (get-programs-from-rss-aux rss)]
    (if (= 0 c)
      (do (debug "reached limit fetching RSS")
          [total pgms])
      (if (or (= 0 total) (> 0 total))
        (do (debug (format "retry fetching RSS #%d (%d) caused by wrong total number: %d" page c total))
            (.sleep TimeUnit/SECONDS WAIT)
            (recur (dec c) (get-nico-rss page) (get-programs-from-rss-aux rss)))
        (if (and (= 0 (count pgms)) (> total (* 18 page)))
          (do (debug (format "retry fetching RSS #%d (%d) caused by lack of programs: %d,%d,%d"
                             page c total (* 18 page) (count pgms)))
              (.sleep TimeUnit/SECONDS WAIT)
              (recur (dec c) (get-nico-rss page) (get-programs-from-rss-aux rss)))
          [total pgms])))))
