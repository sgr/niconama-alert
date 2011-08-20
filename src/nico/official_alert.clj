;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "公式のニコ生アラートAPIで番組情報を取得する。
             番組情報の一部までは取れるんだが、全て取得するにはブラウザの認証情報が必要。
             したがってこちらで認証すると、ブラウザでは見られなくなる。
             結局、コミュニティ情報の取得までしか使っていない。"}
    nico.official-alert
  (:require [nico.pgm :as pgm]
	    [time-utils :as tu]
	    [clojure.xml :as xml]
	    [clojure.zip :as zip]
	    [clojure.contrib.pprint :as pp]
	    [clojure.contrib.http.agent :as ha]
	    [clojure.contrib.zip-filter :as zf]
	    [clojure.contrib.zip-filter.xml :as zfx])
  (:import (java.util Date)
	   (java.net CookieHandler CookieManager CookiePolicy)
	   (org.apache.http HttpResponse)
	   (org.apache.http.impl.client BasicCookieStore BasicResponseHandler DefaultHttpClient)
	   (org.apache.http.client.entity UrlEncodedFormEntity)
	   (org.apache.http.client.methods HttpGet HttpPost)
	   (org.apache.http.client.protocol ClientContext)
	   (org.apache.http.message BasicNameValuePair)
	   (org.apache.http.params CoreProtocolPNames)
	   (org.apache.http.protocol BasicHttpContext HTTP)))

(def *user-agent* "Niconama-alert J/1.0.0")
(def *context* (doto (BasicHttpContext.)
		 (.setAttribute ClientContext/COOKIE_STORE (BasicCookieStore.))))

(defn- print-cookies []
  (for [c (.getCookies (.getAttribute *context* ClientContext/COOKIE_STORE))] (println c)))

(defn- http-call [method context]
  (let [clnt (DefaultHttpClient.)]
    (doto (.getParams clnt)
      (.setParameter CoreProtocolPNames/USER_AGENT *user-agent*))
    (.execute clnt method context)))

(defn- http-post-with [url param-map context]
  (let [form (UrlEncodedFormEntity. (for [[k v] param-map] (BasicNameValuePair. k v)) HTTP/UTF_8)
	mthd (doto (HttpPost. url) (.setEntity form))]
    (http-call mthd context)))

(defn- http-get-with [url context]
  (let [mthd (HttpGet. url)]
    (http-call mthd context)))

;; 認証しクッキーを得る
(defn login-nico [email passwd context]
  (let [raw-res (http-post-with "https://secure.nicovideo.jp/secure/login?site=nicolive"
				{"next_url" "", "mail" email, "password" passwd}
				context)]
    (println raw-res)))

;; 認証APIでチケットを得る
(defn- get-ticket [email passwd context]
  (let [raw-res (http-post-with "https://secure.nicovideo.jp/secure/login?site=nicolive_antenna"
				{"mail" email, "password" passwd}
				context)
	res (xml/parse (-> raw-res .getEntity .getContent))
	status (-> res :attrs :status)]
    (if (.equalsIgnoreCase status "ok")
      (zfx/xml1-> (zip/xml-zip res) :ticket zfx/text)
      (let [err (zfx/xml-> (zip/xml-zip res) :error :description zfx/text)]
	(print err)
	nil))))

(defn- get-alert-status1 [ticket context]
  (let [raw-res (http-get-with
		 (format "http://live.nicovideo.jp/api/getalertstatus?ticket=%s" ticket)
		 context)
	res (xml/parse (-> raw-res .getEntity .getContent))
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

(defn get-alert-status [email passwd]
  (get-alert-status1 (get-ticket email passwd *context*) *context*))

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

;; クッキーの認証情報まで使うわりには得られる情報が不十分。
(defn- get-player-status [pid context]
  (let [raw-res (http-get-with
		 (format "http://watch.live.nicovideo.jp/api/getplayerstatus?v=lv%s" pid)
		 context)
	res (xml/parse (-> raw-res .getEntity .getContent))
	status (-> res :attrs :status)]
    (if (.equalsIgnoreCase status "ok")
      (zip/xml-zip res)
      (let [err (zfx/xml-> (zip/xml-zip res) :error :code zfx/text)]
	(println err)
	nil))))

;; 放送開始からしばらく経つとnot_permittedエラーを返すみたい。
;; また、取れる情報も少ないため今ひとつ。
(defn- get-stream-info [pid context]
  (let [raw-res (http-get-with
		 (format "http://live.nicovideo.jp/api/getstreaminfo/%s" pid)
		 context)
	res (xml/parse (-> raw-res .getEntity .getContent))
	status (-> res :attrs :status)]
    (if (.equalsIgnoreCase status "ok")
      (let [zres (zip/xml-zip res)]
	{:comm_thumbnail (first (zfx/xml-> zres :communityinfo :thumbnail zfx/text))
	 :comm_name (first (zfx/xml-> zres :communityinfo :name zfx/text))}
      (let [err (zfx/xml-> (zip/xml-zip res) :error :code zfx/text)]
	(println err)
	nil)))))

(defn- create-pgm [status info fetched_at]
  (let [item (zfx/xml1-> status :stream)]
    (nico.pgm.Pgm.
     (zfx/xml1-> item :id zfx/text)
     (zfx/xml1-> item :title zfx/text)
     (Date. (Long/parseLong (zfx/xml1-> item :start_time zfx/text)))
     (zfx/xml1-> item :description zfx/text)
     (str "") ; カテゴリの取得方法が不明
     (str "http://live.nicovideo.jp/watch/" (zfx/xml1-> item :id zfx/text))
     (if info (:comm_thumbnail info)
	 (str "http://com.nicovideo.jp/community/" (zfx/xml1-> item :default_community zfx/text)))
     (zfx/xml1-> item :owner_name zfx/text)
     false ; コミュ限を取る方法が不明
     (Integer/parseInt (zfx/xml1-> item :watch_count zfx/text))
     (zfx/xml1-> item :provider_type zfx/text)
     (Integer/parseInt (zfx/xml1-> item :comment_count zfx/text))
     (if info (:comm_name info) "") ; コミュニティ名
     (zfx/xml1-> item :default_community zfx/text)
     false
     fetched_at)))

(defn listen [alert-status pgm-fn context]
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
		    (if-let [ps (get-player-status pid context)]
		      (let [info (get-stream-info pid context)]
			(if-let [pgm (create-pgm ps info (tu/now))]
			  (pgm-fn pgm)
			  (println "[ERROR] couldn't create pgm!")))
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

