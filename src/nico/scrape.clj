;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコ生のページから情報を取得する。"}
    nico.scrape
  (:require [net.cgrand.enlive-html :as html]
	    [str-utils :as s]
	    [clojure.contrib.string :as cs])
  (:import (java.util Calendar Date GregorianCalendar Locale TimeZone)))

(def *base-url* "http://live.nicovideo.jp/watch/")

(defn- fetch-pgm-info1
  [pid]
  (let [link (str *base-url* pid)
	h (html/html-resource (java.net.URL. link))
	base (-> (html/select h [:base]) first :attrs :href)
	infobox (first (html/select h [:div.infobox]))
	title (s/cleanup (first (html/select infobox [:h2 :> html/text-node])))
	desc (cs/join " " (for [n (html/select infobox [:div.bgm :> :div :> html/text-node])]
			    (s/cleanup n)))
	pubdate (let [[sday sopen sstart] (html/select infobox
						       [:div.kaijo :> :strong :> html/text-node])]
		  (let [[yyyy MM dd] (for [x (rest (re-find #"(\d{4})/(\d{2})/(\d{2})" sday))]
				       (Integer/parseInt x))
			[ohh omm] (for [x (.split sopen ":")] (Integer/parseInt x))
			[shh smm] (for [x (.split sstart ":")] (Integer/parseInt x))
			c (doto (GregorianCalendar. yyyy (dec MM) dd shh smm)
			    (.setTimeZone (TimeZone/getTimeZone "Asia/Tokyo")))]
		    (do
		      ;; 開場23:5x、開演00:0xの場合に対応
		      (if (and (= 23 ohh) (= 0 shh)) (.add c Calendar/DAY_OF_MONTH 1))
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
		    (let [as (html/select comm [:div.shosai :a])]
		      (if (= 1 (count as))
			(s/cleanup (-> (first as) :content first))
			(s/cleanup (-> (second as) :content first))))
		    nil)
	owner_name (condp = type
		       :community (s/cleanup
				   (first
				    (html/select
				     comm [:strong.nicopedia_nushi :> :a :> html/text-node])))
		       :channel (s/cleanup
				 (second
				  (html/select comm [:strong :> html/text-node])))
		       nil)
	thumbnail (let [bn (-> (html/select infobox [:div.bn :img]) first :attrs :src)]
		    (if (= type :community) bn (str base bn)))]
    {:title title
     :pubdate pubdate
     :desc desc
     :category category
     :link link
     :thumbnail thumbnail
     :owner_name owner_name
     :member_only member_only
     :type type
     :comm_name comm_name}))

(defn- fetch-pgm-info2 [pid]
  (try
    (fetch-pgm-info1 pid)
    (catch NullPointerException _ nil)))

(defn fetch-pgm-info
  "ニコ生の番組ページから番組情報を取得する。"
  [pid]
  (if-let [pgm (fetch-pgm-info2 pid)]
    pgm
    (do (Thread/sleep 1000) (recur pid))))
