;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Utility for networking."}
  net-utils
  (:use [clojure.tools.logging]
        [clj-http.conn-mgr :only [make-reusable-conn-manager]])
  (:require [io-utils :as io]
            [clj-http.client :as client])
  (:import [java.io File IOException]
           [java.util.concurrent TimeUnit]
           [java.net SocketTimeoutException HttpURLConnection]
           [org.apache.http.protocol BasicHttpContext]
           [org.apache.http.impl.client DefaultHttpClient]
           [org.apache.http.client.methods HttpGet]
           [org.apache.http.client.cache CacheResponseStatus]
           [org.apache.http.impl.client.cache ManagedHttpCacheStorage CacheConfig CachingHttpClient FileResourceFactory]))

(def ^{:private true} CONNECT-TIMEOUT 5000)
(def ^{:private true} READ-TIMEOUT    8000)
(def ^{:private true} INTERVAL-RETRY  2000)

(def ^{:private true} HTTP-OPTS
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

(let [cm (make-reusable-conn-manager {:threads 2})
      default-opts (assoc HTTP-OPTS :connection-manager cm)]
  (defn http-get [url & opts]
    (client/get url (if opts (apply assoc default-opts opts) default-opts)))
  (defn http-post [url & opts]
    (client/post url (if opts (apply assoc default-opts opts) default-opts))))

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
      cache-storage (ManagedHttpCacheStorage. cache-config)
      ^org.apache.http.impl.conn.PoolingClientConnectionManager cm (make-reusable-conn-manager {:threads 2})
      client (atom nil)]
  (defn close-conns [] (.closeExpiredConnections cm))

  (defn clear-cache []
    (.shutdown cache-storage)
    (when @cache-dir (io/delete-all-files @cache-dir)))

  (defn clean-cache []
    (debug (format "CLEANING HTTP CACHE RESOURCES"))
    (.cleanResources cache-storage))

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

  (defn ^java.io.InputStream fetch-with-caching
    [^String url handler]
    (when-not @client
      (reset! client (CachingHttpClient. (DefaultHttpClient. cm) @resource-factory cache-storage cache-config)))
    (let [context (BasicHttpContext.)
          request (HttpGet. url)]
      (try
        (trace (format "fetching content from %s" url ))
        (let [response (.execute @client request context)
              status-code (-> response .getStatusLine .getStatusCode)
              cache-status (.getAttribute context CachingHttpClient/CACHE_RESPONSE_STATUS)]
          (trace (format "%s: (%s)"
                         (condp = cache-status
                           CacheResponseStatus/CACHE_HIT  "CACHE_HIT"
                           CacheResponseStatus/CACHE_MISS "CACHE_MISS"
                           CacheResponseStatus/CACHE_MODULE_RESPONSE "CACHE_MODULE_RESPONSE"
                           CacheResponseStatus/VALIDATED  "VALIDATED"
                           (format "unknown cache status (%s)" (pr-str cache-status)))
                         url))
          (condp = status-code
            200 (handler response)
            (do (debug (format "status code (%d) is returned" status-code))
                nil)))
        (catch Exception e
          (.abort request)
          (error e (format "failed fetching contnet from: %s" url)))
        (finally
          (.releaseConnection request))))))
