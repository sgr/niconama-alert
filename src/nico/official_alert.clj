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
  (:import (java.util Date)
	   (java.net CookieHandler CookieManager CookiePolicy)))

(def *user-agent* "Niconama-alert J/1.0.0")
(def *cookie-mgr* (let [cmgr (CookieManager.)]
		    (.setCookiePolicy cmgr CookiePolicy/ACCEPT_ALL)
		    (CookieHandler/setDefault cmgr)
		    cmgr))
(defn- cookies [] (.getCookies (.getCookieStore *cookie-mgr*)))
(defn- print-cookies [] (println (count (cookies))) (for [c (cookies)] (println c)))

;; このコードでは認証後のクッキーが取れない。なんでだろう。
(defn login-nico [email passwd]
  (let [agnt (ha/http-agent
	      "https://secure.nicovideo.jp/secure/login?site=nicolive"
	      :headers {"user-agent" *user-agent*}
	      :method "POST" :body (format "next_url=&mail=%s&password=%s" email passwd)
	      :connect-timeout 10000
	      :read-timeout 10000)
	s (ha/string agnt)]
    (println (ha/request-headers agnt))
    (println s)
    (print-cookies)))

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
      (first (zfx/xml-> (zip/xml-zip res) :ticket zfx/text))
      (let [err (zfx/xml-> (zip/xml-zip res) :error :description zfx/text)]
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
	{:user_id (first (zfx/xml-> xz :user_id zfx/text))
	 :user_name (first (zfx/xml-> xz :user_name zfx/text))
	 :comms (zfx/xml-> xz :communities :community_id zfx/text)
	 :addr (first (zfx/xml-> xz :ms :addr zfx/text))
	 :port (Integer/parseInt (first (zfx/xml-> xz :ms :port zfx/text)))
	 :thrd (first (zfx/xml-> xz :ms :thread zfx/text))})
      (let [err (zfx/xml-> (zip/xml-zip res) :error :code zfx/text)]
	(println err)
	nil))))

(defn- create-pgm-by-getstreaminfo
  "getstreaminfoで得られた情報から番組情報を生成する。が、足りない情報がポロポロあって使えない・・・"
  [id comm_id owner_id pubdate zipped-res fetched_at]
  (nico.pgm.Pgm.
   (str "lv" id)
   (first (zfx/xml-> zipped-res :streaminfo :title zfx/text))
   pubdate
   (first (zfx/xml-> zipped-res :streaminfo :description zfx/text))
   nil ;category
   (str "http://live.nicovideo.jp/watch/lv" id)
   (first (zfx/xml-> zipped-res :communityinfo :thumbnail zfx/text))
   owner_id ;owner_name
   nil ;member_only
   nil ;view
   (first (zfx/xml-> zipped-res :streaminfo :provider_type zfx/text))
   nil ;num_res
   (first (zfx/xml-> zipped-res :communityinfo :name zfx/text))
   comm_id
   false
   fetched_at))

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

(defn- get-player-status [pid]
  (let [agnt (ha/http-agent
	      (format "http://watch.live.nicovideo.jp/api/getplayerstatus?v=lv%s" pid)
	      :headers {"user-agent" *user-agent*}
	      :connect-timeout 10000
	      :read-timeout 10000)
	res (xml/parse (ha/stream agnt))
	status (-> res :attrs :status)]
    (if (.equalsIgnoreCase status "ok")
      (zfx/xml-> (zip/xml-zip res) :stream zf/children) 
      (let [err (zfx/xml-> (zip/xml-zip res) :error :code zfx/text)]
	(println err)
	nil))))

(defn- get-stream-info [pid]
  (let [agnt (ha/http-agent
	      (format "http://live.nicovideo.jp/api/getstreaminfo/lv%s" pid)
	      :headers {"user-agent" *user-agent*}
	      :connect-timeout 10000
	      :read-timeout 10000)
	res (xml/parse (ha/stream agnt))
	status (-> res :attrs :status)]
    (if (.equalsIgnoreCase status "ok")
      (let [zres (zip/xml-zip res)]
	{:comm_thumbnail (first (zfx/xml-> zres :communityinfo :thumbnail zfx/text))
	 :comm_name (first (zfx/xml-> zres :communityinfo :name zfx/text))}
      (let [err (zfx/xml-> (zip/xml-zip res) :error :code zfx/text)]
	(println err)
	nil)))))


(defn- get-child-elm [tag node]
  (some #(if (= tag (:tag %)) %) (:content node)))

(defn- get-child-content [tag node]
  (first (:content (get-child-elm tag node))))

(defn- get-child-attr [tag attr node]
  (attr (:attrs (get-child-elm tag node))))

(defn- create-pgm [item info fetched_at]
  (nico.pgm.Pgm.
   (get-child-content :id item)
   (get-child-content :title item)
   (Date. (Long/parseLong (get-child-content :start_time item)))
   (get-child-content :description item)
   (str "") ; カテゴリの取得方法が不明
   (str "http://live.nicovideo.jp/watch/" (get-child-content :id item))
   (:comm_thumbnail info)
   (get-child-content :owner_name item)
   false ; コミュ限を取る方法が不明
   (Integer/parseInt (get-child-content :watch_count item))
   (get-child-content :provider_type item)
   (Integer/parseInt (get-child-content :comment_count item))
   (:comm_name info)
   (get-child-content :default_community item)
   false
   fetched_at))

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
		    (if-let [ps (get-player-status pid)]
		      (if-let [info (get-stream-info pid)]
			(if-let [pgm (create-pgm ps info (tu/now))]
			  (pgm-fn pgm)
			  (println "[ERROR] couldn't create pgm!"))
			(println "[ERROR] couldn't get stream info!"))
		      (println "[ERROR] couldn't get player status!"))
		    (println "[ERROR] couldn't parse the chat str!"))
		  (recur (.read rdr) nil))
		(recur (.read rdr) (str s (char c))))))))
    (catch java.io.IOException e (.printStackTrace e) false)
    (catch java.net.SocketTimeoutException e (.printStackTrace e) false)
    (catch java.net.UnknownHostException e (.printStackTrace e) false)
    (catch Exception e (.printStackTrace e) false)))

(defn run-listener [alert-status pgm-fn]
  (.start (Thread. (fn [] (listen alert-status pgm-fn)))))

