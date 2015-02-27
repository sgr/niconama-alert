;; -*- coding: utf-8-unix -*-
(ns nico.net
  (:require [org.httpkit.client :as http]))

(let [TIMEOUT-MSEC 10000
      HTTP-OPTS {:user-agent "NiconamaAlert.clj"
                 :timeout TIMEOUT-MSEC
                 :follow-redirects true}]
  (defn- http-req
    "指定されたurlにリクエストを行い、レスポンスがmapで返る。タイムアウトの場合nilが返る。"
    [method url opts]
    (let [ftr (method url (merge HTTP-OPTS (first opts)))]
      (deref ftr TIMEOUT-MSEC nil)))
  (defn http-get
    "指定されたurlにGETリクエストを行い、レスポンスがmapで返る。タイムアウトの場合nilが返る。"
    [url & opts]
    (http-req http/get url opts))
  (defn http-post
    "指定されたurlにPOSTリクエストを行い、レスポンスがmapで返る。タイムアウトの場合nilが返る。"
    [url & opts]
    (http-req http/post url opts)))
