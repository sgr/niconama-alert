;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコニコ生放送配信中の放送情報RSSより、放送情報を取得する。
             RSSは文字数制限があるようで、タイトルや説明が切れることがある。
             また、絵文字の類いがそのまま？RSSに混入するようなので、まずいものは除去する。
             RSS取得中に番組情報が更新されたことが検知された場合は、取得を中止する。
             再実行は呼び出し側で検討すること。"}
    nico.rss
  (:require [clojure.xml :as xml]
	    [clojure.zip :as zip]
	    [clojure.contrib.zip-filter :as zf]
	    [clojure.contrib.zip-filter.xml :as zfx]
	    [clojure.contrib.duck-streams :as ds]
	    [clojure.contrib.http.agent :as ha]
	    [nico.pgm :as pgm]
	    [time-utils :as tu])
  (:import (java.text SimpleDateFormat)
	   (java.io ByteArrayInputStream)
	   (java.net SocketTimeoutException)
	   (java.util Locale)))

(defn- printe
  [^Exception e]
  (println (format " failed fetching RSS: %s: %s" (-> e .getClass .getName) (.getMessage e))))

(defn- cleanup
  "絵文字など制御文字扱いになる文字を削除する"
  [s]
  (.replaceAll s "[\\00-\\x1f\\x7f]" ""))

(defn- get-nico-rss
  [page]
  (try
    (let [s (ds/slurp* (format "http://live.nicovideo.jp/recent/rss?p=%s" page))
	  cs (cleanup s)]
      (xml/parse (ByteArrayInputStream. (.getBytes cs "UTF-8"))))
    (catch Exception e (do (printe e) {}))))

;; タイムアウト値を設定。これ、SunのJREでないときはどうしたらよいだろうか？
(System/setProperty "sun.net.client.defaultConnectTimeout" "10000")
(System/setProperty "sun.net.client.defaultReadTimeout" "10000")

(defn- get-nico-rss-old
  "get a RSS page of nicolive info and parse it to RSS map."
  [page]
  (print " creating http agent: ")
  (try
    (let [agnt (ha/http-agent (format "http://live.nicovideo.jp/recent/rss?p=%s" page)
			      :connect-timeout 500
			      :read-timeout 300)
	  conn (:clojure.contrib.http.agent/connection @agnt)]
      (print (format " conn cto: %d, rto: %d - " (.getConnectTimeout conn) (.getReadTimeout conn)))
      (let [s (cleanup (ha/string agnt))]
	(if-let [er (agent-error agnt)]
	  (println (format "errors: %s" er))
	  (println "done"))
	(xml/parse (ByteArrayInputStream. (.getBytes s "UTF-8")))))
    (catch Exception e (do (printe e) {}))))

(defn- get-programs-count
  "get the total programs count."
  ([] (get-programs-count (get-nico-rss 1)))
  ([rss] (try
	   (Integer/parseInt
	    (first (zfx/xml-> (zip/xml-zip rss) :channel :nicolive:total_count zfx/text)))
	   (catch NumberFormatException e (do (printe e) 0)))))

(defn- get-child-elm [tag node]
  (some #(if (= tag (:tag %)) %) (:content node)))

(defn- get-child-content [tag node]
  (first (:content (get-child-elm tag node))))

(defn- get-child-attr [tag attr node]
  (attr (:attrs (get-child-elm tag node))))

(defn- create-pgm [item fetched_at]
  (nico.pgm.Pgm.
   (get-child-content :guid item)
   (get-child-content :title item)
   (.parse (SimpleDateFormat. "EEE, dd MMM yyyy HH:mm:ss Z" Locale/ENGLISH)
	   (get-child-content :pubDate item))
   (get-child-content :description item)
   (get-child-content :category item)
   (get-child-content :link item)
   (get-child-attr :media:thumbnail :url item)
   (get-child-content :nicolive:owner_name item)
   (Boolean/parseBoolean (get-child-content :nicolive:member_only item))
   (Integer/parseInt (get-child-content :nicolive:view item))
   (get-child-content :nicolive:type item)
   (Integer/parseInt (get-child-content :nicolive:num_res item))
   (get-child-content :nicolive:community_name item)
   (get-child-content :nicolive:community_id item)
   false
   fetched_at))

(defn- get-programs-from-rss-page [rss]
  (for [item (for [x (zfx/xml-> (zip/xml-zip rss) :channel zf/children)
		   :when (= :item (:tag (first x)))] (first x))]
    (create-pgm item (tu/now))))

(defn- earliest-pubdate [earliest pgms]
  (reduce #(if (tu/earlier? %1 %2) %1 %2)
	  earliest (for [pgm pgms :when (:comm_id pgm)] (:pubdate pgm))))

(defn get-programs-from-rss
  [update-fn]
  (try
    (loop [page 1, earliest (tu/now), fetched #{}, total (get-programs-count)]
      (let [rss (get-nico-rss page)
	    cur_pgms (get-programs-from-rss-page rss)
	    fetched-updated (if (pos? (count cur_pgms))
			      (apply conj fetched (for [pgm cur_pgms] (:id pgm)))
			      fetched)
	    earliest-updated (earliest-pubdate earliest cur_pgms)]
	(update-fn cur_pgms (count fetched) total (inc page))
	(cond
	 (or 
	  (>= (+ (count fetched) (count cur_pgms)) total) ;; 総番組数分取得したら、取得完了
	  (= (count cur_pgms) 0) ;; ひとつも番組が取れない場合は中止
	  (> (reduce #(if (contains? fetched (:id %2)) (inc %1) %1) 0 cur_pgms)
	     (* 0.9 (count cur_pgms))) ;; 取得済みの番組情報との重複率が90%を超えていたら、取得中止
	  (not (= total (get-programs-count rss)))) ;; 総番組数が変更されていたら、取得中止
	 (list earliest-updated fetched-updated total)
	 :else ;; そうでなければ次のページを取得しに行く
	 (recur (inc page) earliest-updated fetched-updated total))))
    (catch Exception e (printe e) (list (tu/now) #{} 0))))
