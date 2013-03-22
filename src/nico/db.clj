;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "データベース操作関数を集約"}
  nico.db
  (:use [clojure.tools.logging])
  (:require [clojure.java.jdbc :as jdbc]
            [hook-utils :as hu]
            [io-utils :as io]
	    [time-utils :as tu]
            [nico.prefs :as prefs])
  (:import [java.sql Connection DriverManager PreparedStatement ResultSet SQLException Timestamp]
           [java.util Calendar Date]
           [java.util.concurrent Callable Future FutureTask LinkedBlockingQueue ThreadPoolExecutor TimeUnit
            CancellationException CountDownLatch ExecutionException ThreadPoolExecutor$DiscardPolicy]))

(def ^{:private true} DB-CLASSNAME "org.sqlite.JDBC")
(def ^{:private true} KEEP-ALIVE 5)       ;; ワーカースレッド待機時間
(def ^{:private true} INTERVAL-UPDATE 3) ;; 更新フック呼び出し間隔
(def ^{:private true} INTERVAL-RETRY  1) ;; SQLiteデータベースロック時のリトライ間隔
(def ^{:private true} THRESHOLD-FREELIST 500) ;; SQLiteのフリーリスト閾値

(def ^{:private true} LEN_COMM_ID        10)
(def LEN_COMM_NAME      64)
(def ^{:private true} LEN_COMM_THUMBNAIL 64)

(hu/defhook db :shutdown :updated)      ;; データベース関連フック

(defn- varchar [len] (format "varchar(%d)" len))

(defn- create-comms [db]
  (jdbc/db-do-commands
   db true
   (jdbc/create-table-ddl
    :comms
    [:id (varchar LEN_COMM_ID) "PRIMARY KEY"]
    [:comm_name (varchar LEN_COMM_NAME)]
    [:thumbnail (varchar LEN_COMM_THUMBNAIL)]
    [:fetched_at :timestamp]
    [:updated_at :timestamp]))
  (jdbc/db-do-commands
   db true
   "CREATE INDEX idx_comms_id ON comms(id)"
   "CREATE INDEX idx_comms_name ON comms(comm_name)"
   "CREATE INDEX idx_comms_updated_at ON comms(updated_at)"))

(def ^{:private true} LEN_PGM_ID          12)
(def LEN_PGM_TITLE       64)
(def LEN_PGM_DESCRIPTION 256)
(def ^{:private true} LEN_PGM_CATEGORY    24)
(def ^{:private true} LEN_PGM_LINK        42)
(def LEN_PGM_OWNER_NAME  64)

(defn- create-pgms [db]
  (jdbc/db-do-commands
   db true
   (jdbc/create-table-ddl
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
    [:updated_at :timestamp]))
  (jdbc/db-do-commands
   db true
   "CREATE INDEX idx_pgms_id ON pgms(id)"
   "CREATE INDEX idx_pgms_title ON pgms(title)"
   "CREATE INDEX idx_pgms_pubdate ON pgms(pubdate)"
   "CREATE INDEX idx_pgms_description ON pgms(description)"
   "CREATE INDEX idx_pgms_category ON pgms(category)"
   "CREATE INDEX idx_pgms_owner_name ON pgms(owner_name)"
   "CREATE INDEX idx_pgms_comm_id ON pgms(comm_id)"
   "CREATE INDEX idx_pgms_updated_at ON pgms(updated_at)"))

(defn- create-db [db]
  (info "CREATE DB: PGMS")
  (create-pgms db)
  (info "CREATE DB: COMMS")
  (create-comms db))

(defn- delete-db-file [path] (io/delete-all-files path))

(defn ^Callable wrap-conn-callable [f db-spec]
  (proxy [Callable] []
    (call []
      (try
        (f db-spec)
        (catch Exception e
          (error e (format "failed execute f: %s" (pr-str f)))
          e)))))

(gen-class
 :name nico.db.Queue
 :extends java.util.concurrent.ThreadPoolExecutor
 :prefix "dq-"
 :constructors {[clojure.lang.IPersistentMap]
                [int int long java.util.concurrent.TimeUnit java.util.concurrent.BlockingQueue]}
 :state state
 :init init
 :post-init post-init
 :exposes-methods {submit submitSuper
                   beforeExecute beforeExecuteSuper
                   afterExecute  afterExecuteSuper
                   shutdown shutdownSuper}
 :methods [[lastUpdated [] java.util.Date]])

(defn- dq-init [^clojure.lang.IPersistentMap db-spec]
  [[1 1 KEEP-ALIVE TimeUnit/SECONDS (LinkedBlockingQueue.)]
   (atom {:db-spec          db-spec
          :last-hook-called (tu/now)
          :last-updated     (tu/now)})])

(defn- dq-post-init [^nico.db.Queue this db-spec]
  (.setRejectedExecutionHandler this (ThreadPoolExecutor$DiscardPolicy.)))

(defn- dq-submit [^nico.db.Queue this ^clojure.lang.IFn f]
  (trace (format "submitted queue: %d, %s" (-> this .getQueue .size) (pr-str f)))
  (.submitSuper this (wrap-conn-callable f (:db-spec @(.state this)))))

(defn- dq-beforeExecute [^nico.db.Queue this ^Thread t ^Runnable r]
  (trace (format "beforeExecute queue: %d, %s, %s" (-> this .getQueue .size) (pr-str (:db-spec @(.state this))) (pr-str r)))
  (.beforeExecuteSuper this t r))

(defn- dq-afterExecute [^nico.db.Queue this ^Runnable r ^Throwable t]
  (.afterExecuteSuper this r t)
  (trace (format "afterExecute queue: %d, %s" (-> this .getQueue .size) (pr-str r)))
  (if (and (nil? t) (instance? Future r))
    (try
      (when (.isDone ^Future r)
        (trace (format "afterExecute result: %s" (pr-str (.get ^Future r))))
        (let [now (tu/now)
              last-hook-called (get @(.state this) :last-hook-called)]
          (swap! (.state this) assoc :last-updated now)
          ;; DB更新フックの実行
          (when (or (= 0 (-> this .getQueue .size))
                    (not (tu/within? last-hook-called now INTERVAL-UPDATE)))
            (run-db-hooks :updated)
            (swap! (.state this) assoc :last-hook-called now))))
      (catch CancellationException ce
        (error ce "!!!   CancellationException"))
      (catch ExecutionException ee
        (error ee "!!!!  ExecutionException"))
      (catch InterruptedException ie
        (error ie "!!!!! InterruptedException")
        (.interrupt (Thread/currentThread))))
    (error t (format "An error is occurred: %s" (pr-str r)))))

(defn- dq-shutdown [^nico.db.Queue this]
  (debug "shutting down the thread pool...")
  (.shutdownSuper this)
  (debug "await the thread pool termination")
  (.awaitTermination this 10 TimeUnit/SECONDS))

(defn- dq-lastUpdated [^nico.db.Queue this]
  (:last-updated @(.state this)))


(let [db-spec {:classname DB-CLASSNAME
               :subprotocol "sqlite"
               :subname ":memory:"}
      queue (atom nil)
      conn (atom nil)
      ro-conn (atom nil)
      latch (atom nil)]
  (defn last-updated [] (when @queue (.lastUpdated ^nico.db.Queue @queue)))
  (defn queue-size [] (when @queue (.size (.getQueue ^nico.db.Queue @queue))))
  (defn- create-conn []
    (DriverManager/getConnection (format "jdbc:%s:%s" (:subprotocol db-spec) (:subname db-spec))))
  (defn db [] (if @conn {:connection @conn} db-spec))
  (defn- vacuum []
    (when @conn
      (reset! latch (CountDownLatch. 1))
      (try
        (debug "VACUUM!")
        (doto (.createStatement ^java.sql.Connection @conn)
          (.executeUpdate "VACUUM")
          (.executeUpdate "REINDEX"))
        (catch Exception e
          (error e ("vacuum failed")))
        (finally
          (.countDown @latch)
          (reset! latch nil)))))
  (defn enqueue [f]
    (when @queue
      (.submit ^nico.db.Queue @queue f)))
  (defn req-ro [f]
    (letfn [(req-ro-aux [f conn]
              (try
                (f {:connection conn})
                (catch Exception e e)))]
      (when @ro-conn
        (when @latch (.await @latch)) ; await finishing vacuum
        (when-let [result (req-ro-aux f @ro-conn)]
          (condp instance? result
            Exception (let [msg (.getMessage result)]
                        (if (re-find #"\[SQLITE_BUSY\]*" msg)
                          (do (warn result (format "retry requesting..."))
                              (.sleep TimeUnit/SECONDS INTERVAL-RETRY)
                              (recur f))
                          (error result (format "failed req-ro: %s" msg))))
            result)))))
  (defn ro-pstmt-fn [^String sql]
    (let [pstmt (jdbc/prepare-statement @ro-conn sql :concurrency :read-only)]
      (fn [f]
        (when @latch (.await @latch)) ; await finishing vacuum
        (if-let [conn (.getConnection pstmt)]
          (let [^ResultSet rs (.executeQuery pstmt)]
            (try
              (f (jdbc/resultset-seq rs))
              (catch Exception e (error e (format "failed search-pgms-by-pstmt: %s" pstmt)))
              (finally (.close rs))))
          (do
            (error (format "invalid nil connection: %s" (pr-str pstmt)))
            {})))))
  (defn init []
    (Class/forName DB-CLASSNAME)
    (let [c (create-conn)]
      (reset! conn c)
      (reset! ro-conn c)
      (info (format "initialized Database: %s" (pr-str c)))
      (reset! queue (nico.db.Queue. {:connection c}));(assoc db-spec :connection c)))
      (.get ^Future (enqueue create-db))))
  (defn- close-conn [conn]
    (try
      (.close @conn)
      (reset! conn nil)
      (catch Exception e
        (error e (format "failed closing conn: %s" (pr-str @conn))))))
  (defn shutdown []
    (debug "called shutdown database!!!")
    (let [q @queue]
      (reset! queue nil)
      (.shutdown q))
    (run-db-hooks :shutdown)
    (when @ro-conn (close-conn ro-conn))
    (when @conn (close-conn conn))))

(defn- maintenance-db []
  (letfn [(pragma-page-count []
            (req-ro #(let [{:keys [page_count]}
                           (jdbc/query %1 ["PRAGMA page_count"] :result-set-fn first)]
                       page_count)))
          (pragma-freelist-count []
            (req-ro #(let [{:keys [freelist_count]}
                           (jdbc/query %1 ["PRAGMA freelist_count"] :result-set-fn first)]
                       freelist_count)))]
    (let [page_count (pragma-page-count) freelist_count (pragma-freelist-count)]
      (trace (format "page_count: %d, freelist_count: %d" page_count freelist_count))
      (when (< THRESHOLD-FREELIST freelist_count)
        (enqueue (fn [db]
                   (info (format "page_count: %d, freelist_count: %d" page_count freelist_count))
                   (vacuum)
                   (info (format "page_count: %d, freelist_count: %d" (pragma-page-count) (pragma-freelist-count)))))))))

(add-db-hook :updated maintenance-db)
