;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "公式のニコ生アラートAPIで番組情報を取得する。
             番組情報の一部までは取れるんだが、全て取得するにはブラウザの認証情報が必要。
             結局、コミュニティ情報の取得までしか使っていない。"}
    nico.official-alert
  (:require [nico.pgm :as pgm]
	    [time-utils :as tu]
	    [clojure.xml :as xml]
	    [clojure.zip :as zip]
	    [clojure.contrib.zip-filter :as zf]
	    [clojure.contrib.zip-filter.xml :as zfx]
	    [clojure.contrib.http.agent :as ha])
  (:import (java.util Date)))

(def *user-agent* "Niconama-alert J/1.0.0")

;; 認証APIでチケットを得る
(defn- get-ticket [email passwd]
  (let [agnt (ha/http-agent
	      "https://secure.nicovideo.jp/secure/login?site=nicolive_antenna"
	      :headers {"user-agent" *user-agent*}
	      :method "POST" :body (format "mail=%s&password=%s" email passwd)
	      :connect-timeout 10000
	      :read-timeout 10000)
	res (xml/parse (ha/stream agnt))
	status (-> res :attrs :status)]
    (if (.equalsIgnoreCase status "ok")
      (zfx/xml1-> (zip/xml-zip res) :ticket zfx/text)
      (let [err (zfx/xml-> (zip/xml-zip res) :error :description zfx/text)]
	(print err)
	nil))))

(defn- get-alert-status1 [ticket]
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
	{:user_id (zfx/xml1-> xz :user_id zfx/text)
	 :user_name (zfx/xml1-> xz :user_name zfx/text)
	 :comms (zfx/xml-> xz :communities :community_id zfx/text)
	 :addr (zfx/xml1-> xz :ms :addr zfx/text)
	 :port (Integer/parseInt (zfx/xml1-> xz :ms :port zfx/text))
	 :thrd (zfx/xml1-> xz :ms :thread zfx/text)})
      (let [err (zfx/xml-> (zip/xml-zip res) :error :code zfx/text)]
	(println err)
	nil))))

(defn get-alert-status [email passwd]
  (get-alert-status1 (get-ticket email passwd)))

(defn- get-stream-info [pid]
  (let [agnt (ha/http-agent
	      (format "http://live.nicovideo.jp/api/getstreaminfo/lv%s" pid)
	      :headers {"user-agent" *user-agent*}
	      :connect-timeout 10000
	      :read-timeout 10000)
	res (xml/parse (ha/stream agnt))
	status (-> res :attrs :status)]
    (if (.equalsIgnoreCase status "ok")
      (zip/xml-zip res)
      (let [err (zfx/xml-> (zip/xml-zip res) :error :code zfx/text)]
	(println err)
	nil))))

(defn- create-pgm-by-getstreaminfo
  "getstreaminfoで得られた情報から番組情報を生成する。が、足りない情報がポロポロあって使えない・・・"
  [zipped-res fetched_at]
  (let [id (zfx/xml1-> zipped-res :request_id zfx/text)]
    (nico.pgm.Pgm.
     id
     (zfx/xml1-> zipped-res :streaminfo :title zfx/text)
     nil; pubdate
     (zfx/xml1-> zipped-res :streaminfo :description zfx/text)
     nil ;category
     (str "http://live.nicovideo.jp/watch/" id)
     (zfx/xml1-> zipped-res :communityinfo :thumbnail zfx/text)
     nil ;owner_name
     nil ;member_only
     nil ;view
     (zfx/xml1-> zipped-res :streaminfo :provider_type zfx/text)
     nil ;num_res
     (zfx/xml1-> zipped-res :communityinfo :name zfx/text)
     (zfx/xml1-> zipped-res :streaminfo :default_community zfx/text)
     false
     fetched_at)))

(defn- parse-chat-str [chat-str]
  (let [chat (xml/parse (java.io.StringBufferInputStream. chat-str))]
    (if (= :chat (-> chat :tag))
      (let [s (-> chat :content)
	    date (Date. (* 1000 (Long/parseLong (-> chat :attrs :date))))]
	(if s
	  (let [[pid cid uid] (.split (first s) ",")]
	    (list date pid cid uid))
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
		  (if-let [[date pid cid uid] (parse-chat-str s)]
		    (if-let [info (get-stream-info pid)]
		      (if-let [pgm (create-pgm-by-getstreaminfo info (tu/now))]
			(pgm-fn pgm)
			(println "[ERROR] couldn't create pgm!"))
		      (println "[ERROR] couldn't get stream info!"))
		    (println "[ERROR] couldn't parse the chat str!"))
		  (recur (.read rdr) nil))
		(recur (.read rdr) (str s (char c))))))))
    (catch java.io.IOException e (.printStackTrace e) false)
    (catch java.net.SocketTimeoutException e (.printStackTrace e) false)
    (catch java.net.UnknownHostException e (.printStackTrace e) false)
    (catch Exception e (.printStackTrace e) false)))

(defn run-listener [alert-status pgm-fn]
  (.start (Thread. (fn [] (listen alert-status pgm-fn)))))

