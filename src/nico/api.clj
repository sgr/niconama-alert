;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "公式のニコ生アラートAPIで番組情報を取得する。
             番組情報はスクレイピングで取得。"}
  nico.api
  (:use [clojure.contrib.logging])
  (:require [nico.pgm :as pgm]
	    [net-utils :as n]
	    [str-utils :as s]
	    [time-utils :as tu]
	    [clojure.xml :as xml]
	    [clojure.zip :as zip]
	    [clojure.contrib.zip-filter :as zf]
	    [clojure.contrib.zip-filter.xml :as zfx]
	    [clojure.contrib.http.agent :as ha])
  (:import [java.util Date]))

(def *user-agent* "Niconama-alert J/1.0.0")

(defn- http-req
  ([url func] (http-req url nil func))	; GET
  ([url body func]
     (let [agnt (ha/http-agent
		 url
		 :headers {"user-agent" *user-agent*}
		 :method (if body "POST" "GET")
		 :body body
		 :connect-timeout n/*connect-timeout*
		 :read-timeout n/*read-timeout*)
	   raw-res (s/cleanup (ha/string agnt))]
       (if-let [err (agent-error agnt)]
	 (error (format "failed http-req (%s): %s" url err))
	 (func raw-res)))))

(defn- nico-ans-handler [func]
  (fn [raw-res]
    (let [res (xml/parse (s/utf8stream raw-res))
	  status (-> res :attrs :status)]
      (if (.equalsIgnoreCase status "ok")
	(func res)
	(let [err (zfx/xml-> (zip/xml-zip res) :error :description zfx/text)]
	  (error (format "returned failure from server: %s" err))
	  nil)))))

;; 認証APIでチケットを得る
(defn- get-ticket [email passwd]
  (http-req "https://secure.nicovideo.jp/secure/login?site=nicolive_antenna"
	    (format "mail=%s&password=%s" email passwd)
	    (nico-ans-handler
	     (fn [res] (zfx/xml1-> (zip/xml-zip res) :ticket zfx/text)))))

(defn- get-alert-status1 [ticket]
  (http-req (format "http://live.nicovideo.jp/api/getalertstatus?ticket=%s" ticket)
	    (nico-ans-handler
	     (fn [res]
	       (let [xz (zip/xml-zip res)]
		 {:user_id (zfx/xml1-> xz :user_id zfx/text)
		  :user_name (zfx/xml1-> xz :user_name zfx/text)
		  :comms (map #(keyword %) (zfx/xml-> xz :communities :community_id zfx/text))
		  :addr (zfx/xml1-> xz :ms :addr zfx/text)
		  :port (Integer/parseInt (zfx/xml1-> xz :ms :port zfx/text))
		  :thrd (zfx/xml1-> xz :ms :thread zfx/text)})))))

(defn get-alert-status [email passwd]
  (get-alert-status1 (get-ticket email passwd)))

(defn- get-stream-info [pid]
  (http-req (format "http://live.nicovideo.jp/api/getstreaminfo/lv%s" pid)
	    (nico-ans-handler
	     (fn [res] (zip/xml-zip res)))))

(defn- create-pgm-from-getstreaminfo
  "getstreaminfoで得られた情報から番組情報を生成する。が、足りない情報がポロポロあって使えない・・・"
  [zipped-res fetched_at]
  (let [id (zfx/xml1-> zipped-res :request_id zfx/text)]
    (nico.pgm.Pgm.
     (keyword id)
     (zfx/xml1-> zipped-res :streaminfo :title zfx/text)
     nil; pubdate
     (zfx/xml1-> zipped-res :streaminfo :description zfx/text)
     nil ;category
     (str "http://live.nicovideo.jp/watch/" id)
     (zfx/xml1-> zipped-res :communityinfo :thumbnail zfx/text)
     nil ;owner_name
     nil ;member_only
     (zfx/xml1-> zipped-res :streaminfo :provider_type zfx/text)
     (zfx/xml1-> zipped-res :communityinfo :name zfx/text)
     (keyword (zfx/xml1-> zipped-res :streaminfo :default_community zfx/text))
     false
     fetched_at
     fetched_at)))

(defn- parse-chat-str [^String chat-str]
  (try
    (let [chat (xml/parse (java.io.StringBufferInputStream. chat-str))]
      (if (= :chat (-> chat :tag))
	(let [s (-> chat :content)
	      date (Date. (* 1000 (Long/parseLong (-> chat :attrs :date))))]
	  (if s
	    (let [[pid cid uid] (.split (first s) ",")]
	      (list date pid cid uid))
	    nil))
	nil))
    (catch Exception e (error (format "parse error: %s" chat-str e)) nil)))

(defn listen [alert-status connected-fn create-task-fn]
  (with-open [sock (doto (java.net.Socket. (:addr alert-status) (:port alert-status))
		     (.setSoTimeout 60000))
	      rdr (java.io.BufferedReader.
		   (java.io.InputStreamReader. (.getInputStream sock) "UTF8"))
	      wtr (java.io.OutputStreamWriter. (.getOutputStream sock))]
    ;; res_fromを-1200にすると、全ての番組を取得するらしい。
    (let [q (format "<thread thread=\"%s\" version=\"20061206\" res_from=\"-1\"/>\0"
		    (:thrd alert-status))]
      (.write wtr q) (.flush wtr)
      (connected-fn)
      (loop [c (.read rdr) s nil]
	(condp = c
	    -1 (info "******* Connection closed *******")
	    0  (let [received (tu/now)]
		 (if-let [[date id cid uid] (parse-chat-str s)]
		   (let [pid (str "lv" id)] (create-task-fn pid cid uid received))
		   (warn (format "couldn't parse the chat str: %s" s)))
		 (recur (.read rdr) nil))
	    (recur (.read rdr) (str s (char c))))))))
