;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "データベース操作関数を集約"}
  nico.db
  (:use [clojure.tools.logging])
  (:require [clojure.java.jdbc :as jdbc]
            [hook-utils :as hu]
            [io-utils :as io]
            [log-utils :as l]
	    [time-utils :as tu])
  (:import [java.sql Connection DriverManager PreparedStatement ResultSet SQLException Timestamp]
           [java.util Calendar Date]
           [java.util.concurrent Callable Future FutureTask LinkedBlockingQueue ThreadPoolExecutor TimeUnit
            CancellationException ExecutionException ThreadPoolExecutor$DiscardPolicy]))

(def ^{:private true} DB-CLASSNAME "org.sqlite.JDBC")
(def ^{:private true} KEEP-ALIVE 5)       ;; ワーカースレッド待機時間
(def ^{:private true} INTERVAL-UPDATE 3) ;; 更新フック呼び出し間隔

(def ^{:private true} LEN_COMM_ID        10)
(def LEN_COMM_NAME      64)
(def ^{:private true} LEN_COMM_THUMBNAIL 64)

(defn- varchar [len] (format "varchar(%d)" len))

(defn- create-comms []
  (jdbc/create-table
   :comms
   [:id (varchar LEN_COMM_ID) "PRIMARY KEY"]
   [:comm_name (varchar LEN_COMM_NAME)]
   [:thumbnail (varchar LEN_COMM_THUMBNAIL)]
   [:fetched_at :timestamp]
   [:updated_at :timestamp])
  (jdbc/do-commands "CREATE INDEX idx_comms_id ON comms(id)"
                    "CREATE INDEX idx_comms_name ON comms(comm_name)"
                    "CREATE INDEX idx_comms_updated_at ON comms(updated_at)"))

(def ^{:private true} LEN_PGM_ID          12)
(def LEN_PGM_TITLE       64)
(def LEN_PGM_DESCRIPTION 256)
(def ^{:private true} LEN_PGM_CATEGORY    24)
(def ^{:private true} LEN_PGM_LINK        42)
(def LEN_PGM_OWNER_NAME  64)

(defn- create-pgms []
  (jdbc/create-table
   :pgms
   [:id (varchar LEN_PGM_ID) "PRIMARY KEY"]
   [:title (varchar LEN_PGM_TITLE)]
   [:pubdate :timestamp]
   [:description (varchar LEN_PGM_DESCRIPTION)]
   [:category (varchar LEN_PGM_CATEGORY)]
   [:link (varchar LEN_PGM_LINK)]
   [:owner_name (varchar LEN_PGM_OWNER_NAME)]
   [:member_only :boolean]
   [:type :smallint] ;; 0: community 1: channel 2: official
   [:comm_id (varchar LEN_COMM_ID)]
   [:alerted :boolean]
   [:fetched_at :timestamp]
   [:updated_at :timestamp])
  (jdbc/do-commands "CREATE INDEX idx_pgms_id ON pgms(id)"
                    "CREATE INDEX idx_pgms_title ON pgms(title)"
                    "CREATE INDEX idx_pgms_pubdate ON pgms(pubdate)"
                    "CREATE INDEX idx_pgms_description ON pgms(description)"
                    "CREATE INDEX idx_pgms_category ON pgms(category)"
                    "CREATE INDEX idx_pgms_owner_name ON pgms(owner_name)"
                    "CREATE INDEX idx_pgms_comm_id ON pgms(comm_id)"
                    "CREATE INDEX idx_pgms_updated_at ON pgms(updated_at)"))

(defn- create-db []
  (jdbc/do-commands "PRAGMA auto_vacuum=2")
  (jdbc/do-commands "PRAGMA incremental_vacuum(64)")
  (create-pgms)
  (create-comms))

(defn- delete-db-file [path] (io/delete-all-files path))

(let [called_at (atom (tu/now))]
  (hu/defhook db :shutdown :updated)
  (defn- call-hook-updated []
    (let [now (tu/now)]
      (when-not (tu/within? @called_at now INTERVAL-UPDATE)
        (run-db-hooks :updated)
        (reset! called_at now)))))

(let [q (LinkedBlockingQueue.)
      last-updated (atom (tu/now))]
  (defn last-updated [] @last-updated)
  (defn queue-size [] (.size q))
  (defn- create-queue [db-spec]
    (proxy [ThreadPoolExecutor] [0 1 KEEP-ALIVE TimeUnit/SECONDS q]
      (submit [f]
        (let [^Callable c (proxy [Callable] []
                            (call []
                              (try
                                (jdbc/with-connection db-spec (f))
                                (catch Exception e
                                  (error e (format "failed execute f: %s" (pr-str f)))
                                  e))))]
          (trace (format "submit queue: %d, %s" (.size q) (pr-str f)))
          (proxy-super submit c)))
      (beforeExecute [t r]
        (trace (format "beforeExecute queue: %d, %s" (.size q) (pr-str r)))
        (proxy-super beforeExecute t r))
      (afterExecute [r e]
        (proxy-super afterExecute r e)
        (trace (format "afterExecute queue: %d, %s" (.size q) (pr-str r)))
        (if (and (nil? e) (instance? Future r))
          (try
            (when (.isDone r)
              (trace (format "afterExecute result: %s" (pr-str (.get r)))))
            (catch CancellationException ce
              (error ce "!!!   CancellationException"))
            (catch ExecutionException ee
              (error ee "!!!!  ExecutionException"))
            (catch InterruptedException ie
              (error ie "!!!!! InterruptedException")
              (.interrupt (Thread/currentThread))))
          (error e (format "An error is occurred: %s" (pr-str r))))
        (reset! last-updated (tu/now))
        (when (= 0 (.size q))
          (.sleep TimeUnit/SECONDS INTERVAL-UPDATE)
          (call-hook-updated)))
      (shutdown []
        (debug "shutting down the thread pool...")
        (proxy-super shutdown)
        (debug "await the thread pool termination")
        (proxy-super awaitTermination 10 TimeUnit/SECONDS)))))

(let [db-path (io/temp-file-name "nico-" ".db")
      subprotocol "sqlite"
      db-spec {:classname DB-CLASSNAME
               :subprotocol subprotocol
               :subname db-path}
      rw-pool (atom nil)
      ro-conn (atom nil)]
  (defn- create-conn [] (DriverManager/getConnection (format "jdbc:%s:%s" subprotocol db-path)))
  (defn enqueue [f]
    (when @rw-pool
      (.submit ^ThreadPoolExecutor @rw-pool f)))
  (defn req [f]
    (when @rw-pool
      (.get ^FutureTask (enqueue f))))
  (defn req-ro [f]
    (when @ro-conn
      (trace (format "called req-ro: %s" (pr-str f)))
      (let [result (jdbc/with-connection (assoc db-spec :connection @ro-conn)
                     (f))]
        (trace (format "finished req-ro: %s" (pr-str result)))
        result)))
  (defn ro-pstmt [^String sql] (jdbc/prepare-statement @ro-conn sql :concurrency :read-only))
  (defn init []
    (Class/forName DB-CLASSNAME)
    (if (io/delete-all-files db-path)
      (let [c (create-conn)]
        (info (format "initialize Database: %s" db-path))
        (reset! rw-pool (create-queue (assoc db-spec :connection c)))
        (.setRejectedExecutionHandler ^ThreadPoolExecutor @rw-pool (ThreadPoolExecutor$DiscardPolicy.))
        (req create-db)
        (reset! ro-conn c))
      (let [msg (format "failed initializing Database: %s" db-path)]
        (error msg)
        (throw (Exception. msg)))))
  (defn shutdown []
    (debug "called shutdown database!!!")
    (let [pool @rw-pool]
      (reset! rw-pool nil)
      (.shutdown pool))
    (run-db-hooks :shutdown)
    (when @ro-conn
      (try
        (.close @ro-conn)
        (reset! ro-conn nil)
        (catch Exception e
          (error e (format "failed closing conn: %s" (pr-str @ro-conn))))))
    (delete-db-file db-path)))
