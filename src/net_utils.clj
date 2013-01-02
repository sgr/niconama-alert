;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Utility for networking."}
  net-utils
  (:use [clojure.tools.logging])
  (:require [io-utils :as io])
  (:import [java.io File IOException]
           [java.util.concurrent TimeUnit]
           [java.net SocketTimeoutException]
           [org.apache.http.protocol BasicHttpContext]
           [org.apache.http.impl.client DefaultHttpClient]
           [org.apache.http.client.methods HttpGet]
           [org.apache.http.client.cache CacheResponseStatus]
           [org.apache.http.impl.client.cache BasicHttpCacheStorage CacheConfig CachingHttpClient FileResourceFactory]))

(def ^{:private true} CONNECT-TIMEOUT 5000)
(def ^{:private true} READ-TIMEOUT    5000)
(def ^{:private true} INTERVAL-RETRY  2000)

(def HTTP-OPTS
  {:headers {"user-agent" "Niconama-alert J/1.1.0"}
   :socket-timeout CONNECT-TIMEOUT
   :conn-timeout   CONNECT-TIMEOUT})

(defmacro with-http-res [bindings & body]
  (assert (vector? bindings)     "with-http-res: a vector for its binding")
  (assert (= 2 (count bindings)) "with-http-res: two number of forms in binding vector")
  `(let ~bindings
     (let [raw-res# ~(first bindings) status# (:status raw-res#) body# (:body raw-res#)]
       (if (= 200 status#)
         (do ~@body)
         (let [msg# (format "returned HTTP error: %d, %s" status# body#)]
           (error msg#)
           (throw (Exception. msg#)))))))

(defn url-stream
  "returns an input stream of the URL. When any error occurred (ex. HTTP response 50x), it returns an error stream."
  [^String url]
  (letfn [(url-stream-aux [^String url ^Integer connect-timeout ^Integer read-timeout]
            (let [conn (doto (.openConnection (java.net.URL. url))
                         (.setConnectTimeout connect-timeout)
                         (.setReadTimeout read-timeout))]
              (try
                (.getInputStream conn)
                (catch IOException e
                  (warn e (format "caught HTTP response: %d for %s" (.getResponseCode conn) url))
                  (.getErrorStream conn))
                (catch SocketTimeoutException e
                  (warn e (format "timeouted open an input stream from: %s (%d, %d)" url connect-timeout read-timeout))
                  nil))))]
    (if-let [is (url-stream-aux url CONNECT-TIMEOUT READ-TIMEOUT)]
      is
      (do (.sleep TimeUnit/SECONDS INTERVAL-RETRY)
          (recur url)))))

(let [cache-path (atom nil)
      resource-factory (atom nil)
      cache-config (CacheConfig.)
      cache-storage (BasicHttpCacheStorage. cache-config)]
  (defn clear-cache []
    (when @cache-path (io/delete-all-files @cache-path)))

  (defn url-stream-with-caching
    [^String url]
    (try
      (when-not @resource-factory
        (when-not @cache-path
          (reset! cache-path (File. (str (System/getProperty "java.io.tmpdir") File/separator "nico_cache")))
          (when-not (.exists @cache-path) (.mkdir @cache-path))
          (debug (format "cache path: %s" (.getCanonicalPath @cache-path))))
        (reset! resource-factory (FileResourceFactory. @cache-path)))
      (debug (format "fetching content from %s" url ))
      (let [client (CachingHttpClient. (DefaultHttpClient.) @resource-factory cache-storage cache-config)
            context (BasicHttpContext.)
            request (HttpGet. url)
            response (.execute client request context)
            status-code (-> response .getStatusLine .getStatusCode)
            cache-status (.getAttribute context CachingHttpClient/CACHE_RESPONSE_STATUS)]
        (debug (format "response from %s is %s" url
                       (condp = cache-status
                         CacheResponseStatus/CACHE_HIT  "CACHE_HIT"
                         CacheResponseStatus/CACHE_MISS "CACHE_MISS"
                         CacheResponseStatus/CACHE_MODULE_RESPONSE "CACHE_MODULE_RESPONSE"
                         CacheResponseStatus/VALIDATED  "VALIDATED"
                         (format "unknown cache status (%s)" (pr-str cache-status)))))
        (condp = status-code
          200 (-> response .getEntity .getContent)
          (do (debug (format "status code (%d) is returned" status-code))
              nil)))
      (catch Exception e
        (error e (format "failed fetching contnet from: %s" url))))))
