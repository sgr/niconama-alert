;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "公式のニコ生アラートAPIで番組情報を取得する。
             番組情報はスクレイピングで取得。"}
  nico.api
  (:use [clojure.tools.logging])
  (:require [nico.pgm :as pgm]
	    [net-utils :as n]
	    [str-utils :as s]
	    [time-utils :as tu]
	    [clojure.xml :as xml]
	    [clojure.zip :as zip]
	    [clojure.data.zip.xml :as dzx]
	    [clj-http.client :as client])
  (:import [java.util Date]))

(defmacro ^{:private true} with-nico-res [bindings & body]
  (assert (vector? bindings)     "with-nico-res: a vector for its binding")
  (assert (= 2 (count bindings)) "with-nico-res: two number of forms in binding vector")
  `(let ~bindings
     (let [status# (-> ~(first bindings) :attrs :status)]
       (if (.equalsIgnoreCase status# "ok")
         (do ~@body)
         (let [err# (dzx/xml-> (zip/xml-zip ~(first bindings)) :error :description dzx/text)]
           (error (format "returned failure from server: %s" err#))
           nil)))))

;; 認証APIでチケットを得る
(defn- get-ticket [email passwd]
  (n/with-http-res [raw-res (client/post "https://secure.nicovideo.jp/secure/login?site=nicolive_antenna"
                                         (assoc n/HTTP-OPTS :form-params {:mail email :password passwd}))]
    (with-nico-res [res (-> raw-res :body s/cleanup s/utf8stream xml/parse)]
      (dzx/xml1-> (zip/xml-zip res) :ticket dzx/text))))

(defn- get-alert-status1 [ticket]
  (n/with-http-res [raw-res (client/get (format "http://live.nicovideo.jp/api/getalertstatus?ticket=%s" ticket)
                                        n/HTTP-OPTS)]
    (with-nico-res [res (-> raw-res :body s/cleanup s/utf8stream xml/parse)]
      (let [xz (zip/xml-zip res)]
        {:user_id (dzx/xml1-> xz :user_id dzx/text)
         :user_name (dzx/xml1-> xz :user_name dzx/text)
         :comms (dzx/xml-> xz :communities :community_id dzx/text)
         :addr (dzx/xml1-> xz :ms :addr dzx/text)
         :port (Integer/parseInt (dzx/xml1-> xz :ms :port dzx/text))
         :thrd (dzx/xml1-> xz :ms :thread dzx/text)}))))

(defn get-alert-status [email passwd]
  (get-alert-status1 (get-ticket email passwd)))

(defn- get-stream-info [pid]
  (n/with-http-res [raw-res (client/get (format "http://live.nicovideo.jp/api/getstreaminfo/lv%s" pid)
                                        n/HTTP-OPTS)]
    (with-nico-res [res (-> raw-res :body s/cleanup s/utf8stream xml/parse)]
      (zip/xml-zip res))))

(defn- create-pgm-from-getstreaminfo
  "getstreaminfoで得られた情報から番組情報を生成する。が、足りない情報がポロポロあって使えない・・・"
  [zipped-res fetched_at]
  (let [id (dzx/xml1-> zipped-res :request_id dzx/text)]
    (nico.pgm.Pgm.
     (keyword id)
     (dzx/xml1-> zipped-res :streaminfo :title dzx/text)
     nil; pubdate
     (dzx/xml1-> zipped-res :streaminfo :description dzx/text)
     nil ;category
     (str "http://live.nicovideo.jp/watch/" id)
     (dzx/xml1-> zipped-res :communityinfo :thumbnail dzx/text)
     nil ;owner_name
     nil ;member_only
     (dzx/xml1-> zipped-res :streaminfo :provider_type dzx/text)
     (dzx/xml1-> zipped-res :communityinfo :name dzx/text)
     (keyword (dzx/xml1-> zipped-res :streaminfo :default_community dzx/text))
     false
     fetched_at
     fetched_at)))

(defn- parse-chat-str [^String chat-str]
  (try
    (let [chat (-> chat-str s/utf8stream xml/parse)]
      (if (= :chat (-> chat :tag))
	(let [s (-> chat :content)
	      date (Date. (* 1000 (Long/parseLong (-> chat :attrs :date))))]
	  (if s
	    (let [[pid cid uid] (.split (first s) ",")]
	      (list date pid cid uid))
	    nil))
	nil))
    (catch Exception e (error e (format "parse error: %s" chat-str)) nil)))

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
