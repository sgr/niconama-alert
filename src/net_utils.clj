;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Utility for networking."}
  net-utils
  (:use [clojure.tools.logging])
  (:require [io-utils :as io])
  (:import [java.io File IOException]
           [java.util.concurrent TimeUnit]
           [java.net SocketTimeoutException HttpURLConnection]
           [org.apache.http.protocol BasicHttpContext]
           [org.apache.http.impl.client DefaultHttpClient]
           [org.apache.http.client.methods HttpGet]
           [org.apache.http.client.cache CacheResponseStatus]
           [org.apache.http.impl.client.cache BasicHttpCacheStorage CacheConfig CachingHttpClient FileResourceFactory]))

(def ^{:private true} CONNECT-TIMEOUT 5000)
(def ^{:private true} READ-TIMEOUT    8000)
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
            (let [^HttpURLConnection conn (doto (.openConnection (java.net.URL. url))
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

(let [cache-dir (atom nil)
      resource-factory (atom nil)
      cache-config (CacheConfig.)
      cache-storage (BasicHttpCacheStorage. cache-config)]
  (defn clear-cache []
    (when @cache-dir (io/delete-all-files @cache-dir)))

  (defn init-cache
    "This function must be called before using url-stream-with-caching"
    [^File cdir]
    (reset! resource-factory nil)
    (reset! cache-dir cdir)
    (let [cp (.getCanonicalPath ^File @cache-dir)]
      (when (.exists ^File @cache-dir)
        (warn (format "clear existing cache directory: %s" cp))
        (clear-cache))
      (if (.mkdir ^File @cache-dir)
        (do (debug (format "cache directory: %s" cp))
            (reset! resource-factory (FileResourceFactory. @cache-dir)))
        (let [msg (format "failed creating cache direcotry: %s" cp)]
          (error msg)
          (throw (Exception. msg))))))

  (defn ^java.io.InputStream url-stream-with-caching
    [^String url]
    (try
      (trace (format "fetching content from %s" url ))
      (let [client (CachingHttpClient. (DefaultHttpClient.) @resource-factory cache-storage cache-config)
            context (BasicHttpContext.)
            request (HttpGet. url)
            response (.execute client request context)
            status-code (-> response .getStatusLine .getStatusCode)
            cache-status (.getAttribute context CachingHttpClient/CACHE_RESPONSE_STATUS)]
        (debug (format "%s: (%s)"
                       (condp = cache-status
                         CacheResponseStatus/CACHE_HIT  "CACHE_HIT"
                         CacheResponseStatus/CACHE_MISS "CACHE_MISS"
                         CacheResponseStatus/CACHE_MODULE_RESPONSE "CACHE_MODULE_RESPONSE"
                         CacheResponseStatus/VALIDATED  "VALIDATED"
                         (format "unknown cache status (%s)" (pr-str cache-status)))
                        url))
        (condp = status-code
          200 (-> response .getEntity .getContent)
          (do (debug (format "status code (%d) is returned" status-code))
              nil)))
      (catch Exception e
        (error e (format "failed fetching contnet from: %s" url))))))
