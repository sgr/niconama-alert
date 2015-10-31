;; -*- coding: utf-8-unix -*-
(ns nico.net
  (:require [org.httpkit.client :as http]))

(let [TIMEOUT-MSEC 10000
      HTTP-OPTS {:user-agent "NiconamaAlert.clj"
                 :timeout TIMEOUT-MSEC
                 :follow-redirects true}]
  (defn http-get
    "指定されたurlにGETリクエストを行い、レスポンスがFutureで返る。"
    [url & opts]
    (http/get url (merge HTTP-OPTS (first opts))))
  (defn http-post
    "指定されたurlにPOSTリクエストを行い、レスポンスがFutureで返る。"
    [url & opts]
    (http/post url (merge HTTP-OPTS (first opts)))))
