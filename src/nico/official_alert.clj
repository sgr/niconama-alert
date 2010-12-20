;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "公式のニコ生アラートAPIで番組情報を取得する。
             番組情報の一部までは取れるんだが、全て取得するにはブラウザの認証情報が必要。
             結局、コミュニティ情報の取得までしか使っていない。"}
    nico.official-alert
  (:require [nico.pgm :as pgm]
	    [clojure.xml :as xml]
	    [clojure.zip :as zip]
	    [clojure.contrib.zip-filter.xml :as zf]
	    [clojure.contrib.http.agent :as ha])
  (:import (java.util Date)))

(def *user-agent* "NicoLive Listings/1.0.0")

;; 認証APIでチケットを得る
(defn get-ticket [email passwd]
  (let [agnt (ha/http-agent
	      "https://secure.nicovideo.jp/secure/login?site=nicolive_antenna"
	      :headers {"user-agent" *user-agent*}
	      :method "POST" :body (format "mail=%s&password=%s" email passwd)
	      :connect-timeout 10000
	      :read-timeout 10000)
	res (xml/parse (ha/stream agnt))
	status (-> res :attrs :status)]
    (if (.equalsIgnoreCase status "ok")
      (first (zf/xml-> (zip/xml-zip res) :ticket zf/text))
      (let [err (zf/xml-> (zip/xml-zip res) :error :description zf/text)]
	(print err)
	nil))))

(defn get-alert-status [ticket]
  (let [agnt (ha/http-agent
	      (format "http://live.nicovideo.jp/api/getalertstatus?ticket=%s" ticket)
	      :headers {"user-agent" *user-agent*}
	      :method "GET"
	      :connect-timeout 10000
	      :read-timeout 10000)
	res (xml/parse (ha/stream agnt))
	status (-> res :attrs :status)]
    (if (.equalsIgnoreCase status "ok")
      (let [xz (zip/xml-zip res)]
	{:user_id (first (zf/xml-> xz :user_id zf/text))
	 :user_name (first (zf/xml-> xz :user_name zf/text))
	 :comms (zf/xml-> xz :communities :community_id zf/text)
	 :addr (first (zf/xml-> xz :ms :addr zf/text))
	 :port (Integer/parseInt (first (zf/xml-> xz :ms :port zf/text)))
	 :thrd (first (zf/xml-> xz :ms :thread zf/text))})
      (let [err (zf/xml-> (zip/xml-zip res) :error :code zf/text)]
	(println err)
	nil))))

(defn- create-pgm
  "getstreaminfoで得られた情報から番組情報を生成する。が、足りない情報がポロポロあって使えない・・・"
  [id comm_id owner_id date zipped-res]
  (nico.pgm.Pgm.
   (str "lv" id)
   (first (zf/xml-> zipped-res :streaminfo :title zf/text))
   date ;pubdate
   (first (zf/xml-> zipped-res :streaminfo :description zf/text))
   nil ;category
   (str "http://live.nicovideo.jp/watch/lv" id)
   (first (zf/xml-> zipped-res :communityinfo :thumbnail zf/text))
   owner_id ;owner_name
   nil ;member_only
   nil ;view
   (first (zf/xml-> zipped-res :streaminfo :provider_type zf/text))
   nil ;num_res
   (first (zf/xml-> zipped-res :communityinfo :name zf/text))
   comm_id
   false))

(defn- get-stream-info [chat-str]
  (let [chat (xml/parse (java.io.StringBufferInputStream. chat-str))]
    (if (= :chat (-> chat :tag))
      (let [s (-> chat :content)
	    d (Date. (* 1000 (Long/parseLong (-> chat :attrs :date))))]
	(if s
	  (let [[pid cid uid] (.split (first s) ",")
		agnt (ha/http-agent
		      (format "http://live.nicovideo.jp/api/getstreaminfo/lv%s" pid)
		      :headers {"user-agent" *user-agent*}
		      :connect-timeout 10000
		      :read-timeout 10000)
		res (xml/parse (ha/stream agnt))
		status (-> res :attrs :status)]
	    (if (.equalsIgnoreCase status "ok")
	      (let [xz (zip/xml-zip res), pgm (create-pgm pid cid uid d xz)]
		(println pgm)
		pgm)
	      (let [err (zf/xml-> (zip/xml-zip res) :error :code zf/text)]
		(println err)
		nil)))
	  nil))
      nil)))

(defn listen [alert-status pgm-fn]
  (try
    (with-open [clnt (java.net.Socket. (:addr alert-status) (:port alert-status))
		rdr (java.io.BufferedReader.
		     (java.io.InputStreamReader. (.getInputStream clnt) "UTF8"))
		wtr (java.io.OutputStreamWriter. (.getOutputStream clnt))]
      (let [q (format "<thread thread=\"%s\" version=\"20061206\" res_from=\"-1\"/>\0"
		      (:thrd alert-status))]
	(do (.write wtr q) (.flush wtr)
	    (loop [c (.read rdr) s nil]
	      (if (= c 0)
		(do
		  (when-let [pgm (get-stream-info s)] (pgm-fn pgm))
		  (recur (.read rdr) nil))
		(recur (.read rdr) (str s (char c))))))))
    (catch java.io.IOException e (.printStackTrace e) false)
    (catch java.net.SocketTimeoutException e (.printStackTrace e) false)
    (catch java.net.UnknownHostException e (.printStackTrace e) false)
    (catch Exception e (.printStackTrace e) false)))

(defn run-listener [alert-status pgm-fn]
  (.start (Thread. (fn [] (listen alert-status pgm-fn)))))

