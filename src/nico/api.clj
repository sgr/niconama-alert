;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "公式のニコ生アラートAPIで番組情報を取得する。
             番組情報はスクレイピングで取得。"}
  nico.api
  (:use [clojure.contrib.logging])
  (:require [nico.pgm :as pgm]
	    [nico.scrape :as ns]
	    [net-utils :as n]
	    [str-utils :as s]
	    [time-utils :as tu]
	    [clojure.xml :as xml]
	    [clojure.zip :as zip]
	    [clojure.contrib.zip-filter :as zf]
	    [clojure.contrib.zip-filter.xml :as zfx]
	    [clojure.contrib.http.agent :as ha])
  (:import (java.util Date)
	   (java.util.concurrent Executors)))

(def *user-agent* "Niconama-alert J/1.0.0")
(def *nthreads* 3)

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

(defn- create-pgm-from-scrapedinfo
  [pid cid]
  (if-let [info (ns/fetch-pgm-info pid)]
    (nico.pgm.Pgm.
     (keyword pid)
     (:title info)
     (:pubdate info)
     (:desc info)
     (:category info)
     (:link info)
     (:thumbnail info)
     (:owner_name info)
     (:member_only info)
     (:type info)
     (:comm_name info)
     (keyword cid)
     false
     (:fetched_at info)
     (:updated_at info))
    nil))

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

(let [pool (Executors/newFixedThreadPool *nthreads*)
      fetching (ref '())]
  (defn listen [ref-alert-status connected-fn pgm-fn]
    (with-open [sock (let [as (first @ref-alert-status)]
		       (doto (java.net.Socket. (:addr as) (:port as)) (.setSoTimeout 60000)))
		rdr (java.io.BufferedReader.
		     (java.io.InputStreamReader. (.getInputStream sock) "UTF8"))
		wtr (java.io.OutputStreamWriter. (.getOutputStream sock))]
      ;; res_fromを-1200にすると、全ての番組を取得するらしい。
      (let [as (first @ref-alert-status)
	    q (format "<thread thread=\"%s\" version=\"20061206\" res_from=\"-1\"/>\0" (:thrd as))]
	(.write wtr q) (.flush wtr)
	(connected-fn)
	(loop [c (.read rdr) s nil]
	  (condp = c
	      -1 (info "******* Connection closed *******")
	      0 (let [received (tu/now)]
		  (letfn [(f [pid cid uid]
			     ;; 繁忙期は番組ページ閲覧すら重い。
			     ;; 番組ID受信後この関数が呼ばれるまで30分経過していたら諦める。
			     (let [now (tu/now)]
			       (if (tu/within? received now 1800)
				 (if-let [pgm (create-pgm-from-scrapedinfo pid cid)]
				   (do
				     (trace
				      (format
				       "fetched pgm: %s %s pubdate: %s, received: %s, fetched_at: %s"
					      (:id pgm) (:title pgm) (:pubdate pgm)
					      (tu/format-time-long received)
					      (:fetched_at pgm)))
				     (pgm-fn pgm))
				   (warn
				    (format "couldn't create-pgm: %s/%s/%s" pid cid uid)))
				 (warn
				  (format "too late to fetch: %s/%s/%s received: %s, called: %s"
					  pid cid uid
					  (tu/format-time-long received)
					  (tu/format-time-long now))))))]
		    (if-let [[date id cid uid] (parse-chat-str s)]
		      (let [pid (str "lv" id)
			    t (if (some #(contains? (set (:comms %)) (keyword cid))
					@ref-alert-status)
				;; 所属コミュニティの放送は優先的に取得
				(do (trace (format "%s: %s is joined community." pid cid))
				    (future #(f pid cid uid)))
				(do (trace (format "%s: %s isn't your community." pid cid))
				    (.submit pool #(f pid cid uid) :finished)))]
			(dosync (alter fetching conj t)))
		      (warn (format "couldn't parse the chat str: %s" s)))
		    (recur (.read rdr) nil)))
	      (recur (.read rdr) (str s (char c))))))))
  (defn count-fetching []
    (dosync (alter fetching (fn [l] (filter #(not (or (.isDone %) (.isCancelled %))) l))))
    (count @fetching)))

(defn- gen-listener [alert-status pgm-fn]
  (fn []
    (try
      (listen alert-status pgm-fn)
      (catch java.net.SocketTimeoutException e (error "Socket timeout" e) false)
      (catch java.net.UnknownHostException e (error "Unknown host" e) false)
      (catch java.io.IOException e (error "IO error" e) false)
      (catch Exception e (error "Unknown error" e) false))))

(let [listener (atom nil)]
  (defn- run-listener
    ([alert-status]
       (run-listener alert-status (fn [pgm] (println (:title pgm))(pgm/add pgm))))
    ([alert-status pgm-fn]
       (when-not @listener
	 (do
	   (reset! listener (Thread. (gen-listener alert-status pgm-fn)))
	   (.start @listener))))))
