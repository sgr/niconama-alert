;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコニコ生放送の番組情報とその操作"}
  nico.pgm
  (:use [clojure.tools.logging])
  (:require [clojure.java.jdbc :as jdbc]
            [hook-utils :as hu]
            [io-utils :as io]
            [log-utils :as l]
            [query-utils :as q]
            [str-utils :as s]
	    [time-utils :as tu]
            [nico.thumbnail :as thumbnail])
  (:import [clojure.lang Keyword]
           [com.mchange.v2.c3p0 ComboPooledDataSource]
           [java.lang.management ManagementFactory]
           [java.sql Connection DriverManager SQLException Timestamp]
           [java.util Calendar]
           [java.util.concurrent Callable LinkedBlockingQueue ThreadPoolExecutor TimeUnit]
           [javax.swing ImageIcon]))

(def ^{:private true} NTHREADS 3) ;; 番組追加ワーカースレッド数
(def ^{:private true} KEEP-ALIVE 5)       ;; ワーカースレッド待機時間

(def ^{:private true} SCALE 1.05) ;; 番組最大保持数
(def ^{:private true} INTERVAL-CLEAN 180) ;; 古い番組情報を削除する間隔
(def ^{:private true} INTERVAL-UPDATE 3) ;; 更新フック呼び出し間隔

(def ^{:private true} DB-CLASSNAME "org.h2.Driver")

(defrecord Pgm
  [id		;; 番組ID
   title	;; タイトル
   pubdate	;; 開始時刻
   desc		;; 説明
   category	;; カテゴリ
   link		;; 番組へのリンク
   thumbnail	;; コミュニティのサムネイル
   owner_name	;; 放送者名
   member_only	;; コミュ限
   type		;; community or channel
   comm_name	;; コミュニティ名
   comm_id	;; コミュニティID
   alerted	;; アラート済み
   fetched_at	;; 取得時刻
   updated_at])	;; 更新時刻

(def ^{:private true} LEN_COMM_ID        10)
(def ^{:private true} LEN_COMM_NAME      64)
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
(def ^{:private true} LEN_PGM_TITLE       64)
(def ^{:private true} LEN_PGM_DESCRIPTION 256)
(def ^{:private true} LEN_PGM_CATEGORY    24)
(def ^{:private true} LEN_PGM_LINK        42)
(def ^{:private true} LEN_PGM_OWNER_NAME  64)

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

(let [db {:classname DB-CLASSNAME
          :subprotocol "h2"}]
  (defn- create-db [path]
    (jdbc/with-connection (assoc db :subname (format "file:%s" path) :create true)
      (jdbc/do-commands "SET IGNORECASE TRUE")
      (create-pgms)
      (create-comms)))
  (defn- shutdown-db [path]
    (try
      (jdbc/with-connection (assoc db :subname (format "file:%s" path))
        (jdbc/do-commands "SHUTDOWN IMMEDIATELY"))
      (catch Exception e
        (warn e "shutdown db")))))

(defn- pool [path]
  (let [cpds (doto (ComboPooledDataSource.)
               (.setDriverClass DB-CLASSNAME)
               (.setJdbcUrl (format "jdbc:h2:file:%s" path))
               (.setMinPoolSize 0)
               (.setInitialPoolSize 0)
               (.setMaxIdleTime 3)
               (.setMaxIdleTimeExcessConnections 3))]
    {:datasource cpds}))

(defmacro ^{:private true} with-conn-pool [pool-fn error-value & body]
  `(if-let [db# (~pool-fn)]
     (jdbc/with-connection db#
       ~@body)
     ~error-value))

(defn- delete-db-files [path]
  (let [db-file           (str path ".h2.db")
        db-trace-file     (str path ".trace.db")
        db-trace-old-file (str path ".trace.db.old")
        db-lobs-file      (str path ".lobs.db")
        db-lock-file      (str path ".lock.db")]
    (doseq [f [db-file db-trace-file db-trace-old-file db-lobs-file db-lock-file]]
      (io/delete-all-files f))))

(defn- pool-status [pooled-db]
  (let [ds (:datasource pooled-db)]
    [(.getNumBusyConnections ds)
     (.getNumIdleConnections ds)
     (.getNumConnections ds)]))

(let [db-path (io/temp-file-name "nico-")
      ro-conns (atom [])
      pooled-db (atom nil)]
  (hu/defhook db :shutdown)
  (defn init-db []
    (io/delete-all-files db-path)
    (create-db db-path)
    (reset! pooled-db (pool db-path))
    (jdbc/with-connection @pooled-db
      (jdbc/do-commands "SET CACHE_SIZE 8192")
      (jdbc/do-commands "SET MAX_OPERATION_MEMORY 10000")
;;      (jdbc/do-commands "SET MAX_MEMORY_ROWS 1000")
      (jdbc/do-commands "SET MAX_MEMORY_UNDO 1000")))
  (defn shutdown []
    (run-db-hooks :shutdown)
    (let [d @pooled-db]
      (reset! pooled-db nil)
      (doseq [ro-conn @ro-conns] (.close ro-conn))
       (doto (:datasource d)
        (.setInitialPoolSize 0)
        (.setMinPoolSize 0)
        (.setMaxIdleTime 1))
      (loop [[bc ic nc] (pool-status d)]
        (if (< 0 nc)
          (do (debug (format "waiting for closing connections: %d/%d/%d" bc ic nc))
              (.sleep TimeUnit/MILLISECONDS 500)
              (recur (pool-status d)))
          (debug (format "all connections were closed: %d/%d/%d" bc ic nc))))
      (doto (:datasource d)
        (.hardReset)
        (.close))
      (.sleep TimeUnit/SECONDS 1)
;;      (shutdown-db db-path)
      (delete-db-files db-path)))
  (defn- db [] @pooled-db)
  (defn get-ro-conn []
    (let [ro-conn (doto (DriverManager/getConnection (format "jdbc:h2:file:%s;IGNORECASE=TRUE" db-path))
                    (.setReadOnly true))]
      (swap! ro-conns conj ro-conn)
      ro-conn)))

(let [called_at (atom (tu/now))]
  (hu/defhook pgms :updated)
  (defn- call-hook-updated []
    (let [now (tu/now)]
      (when-not (tu/within? @called_at now INTERVAL-UPDATE)
        (run-pgms-hooks :updated)
        (reset! called_at now)))))

(add-pgms-hook :updated #(when-let [db (db)]
                           (let [[bc ic nc] (pool-status db)]
                             (debug (format "Datasource: busy(%d), idle(%d), conns(%d)" bc ic nc)))))

(defn- memory-usage []
  (let [mbean (ManagementFactory/getMemoryMXBean)
        husage (.getHeapMemoryUsage mbean)]
    husage))
(add-pgms-hook :updated #(let [u (memory-usage)]
                           (debug (format "JVM memory usage: init(%d), used(%d), committed(%d), max(%d)"
                                          (.getInit u) (.getUsed u) (.getCommitted u) (.getMax u)))))

(let [total (atom -1)]
  (defn set-total [ntotal]
    (when-not (= @total ntotal)
      (call-hook-updated)
      (reset! total ntotal)))
  (defn get-total [] @total))

(defn- get-row-comm [^String comm_id]
  (with-conn-pool db nil
    (jdbc/with-query-results
      rs [{:concurrency :read-only}
          "SELECT id,comm_name,thumbnail,fetched_at,updated_at FROM comms WHERE id=?" comm_id]
      (first rs))))

(defn get-comm-thumbnail [^Keyword comm_id]
  (debug (format "getting community's thumbnail: %s" comm_id))
  (ImageIcon.
   (try
     (if-let [row-comm (get-row-comm (name comm_id))]
       (let [url (:thumbnail row-comm)]
         (if url (thumbnail/fetch url)
             thumbnail/NO-IMAGE))
       thumbnail/NO-IMAGE)
     (catch Exception e
       (error e (format "failed getting community(%s)'s thumbnail: %s"
                        (name comm_id) (.getMessage e)))
       thumbnail/NO-IMAGE))))

(defn- row-to-pgm [row-pgm]
  (let [row-comm (get-row-comm (:comm_id row-pgm))]
    (nico.pgm.Pgm.
     (keyword (:id row-pgm))
     (:title row-pgm)
     (tu/timestamp-to-date (:pubdate row-pgm))
     (:description row-pgm)
     (:category row-pgm)
     (:link row-pgm)
     (:thumbnail row-comm)
     (:owner_name row-pgm)
     (:member_only row-pgm)
     (condp = (:type row-pgm) 0 :community 1 :channel 2 :official)
     (:comm_name row-comm)
     (keyword (:comm_id row-pgm))
     (:alerted row-pgm)
     (tu/timestamp-to-date (:fetched_at row-pgm))
     (tu/timestamp-to-date (:updated_at row-pgm)))))

(defn- count-pgms-aux []
  (jdbc/with-query-results rs [{:concurrency :read-only} "SELECT COUNT(*) AS cnt FROM pgms"]
    (:cnt (first rs))))

(defn count-pgms [] (with-conn-pool db -1 (count-pgms-aux)))

(defn- get-pgm-aux [^String id]
  (jdbc/with-query-results rs [{:concurrency :read-only} "SELECT * FROM pgms WHERE pgms.id=?" id]
    (first rs)))

(defn get-pgm [^Keyword id]
  (with-conn-pool db nil
    (if-let [row-pgm (get-pgm-aux (name id))] (row-to-pgm row-pgm) nil)))

(defn not-alerted
  "まだアラートを出してない番組なら番組情報を返す。アラート済みならnilを返す。"
  [^Keyword id]
  (letfn [(update-alerted-aux [^String id]
            (try
              (jdbc/transaction
               (let [result (jdbc/update-values :pgms ["id=? AND alerted=FALSE" (name id)] {:alerted true})]
                 (if (= 1 (first result)) true false)))
              (catch Exception e
                (debug e (format "failed updating for " id))
                false)))]
    (with-conn-pool db nil
      (if (update-alerted-aux (name id))
        (if-let [row-pgm (get-pgm-aux (name id))]
          (row-to-pgm row-pgm)
          (error (format "failed get-pgm: %s" (name id))))
        nil))))

(defn- rem-pgm-by-id [^String pid]
  (jdbc/delete-rows :pgms ["id=?" pid]))

(defn- get-row-pgm-by-comm-id [^String comm_id]
  (jdbc/with-query-results rs [{:concurrency :read-only} "SELECT * FROM pgms WHERE comm_id=?" comm_id]
    (first rs)))

(defn- merge-pgm [row-pgm ^Pgm pgm]
  (letfn [(longer-for [k x ^Pgm y]
            (s/longer (get x k)
                      (s/trim-to (get y k) (condp = k :title LEN_PGM_TITLE :description LEN_PGM_DESCRIPTION))))
          (later [x y]
            (cond (and x y) (if (tu/later? x y) x y)
                  (nil? x) y
                  (nil? y) x
                  :else nil))
          (later-for [k x ^Pgm y]
            (tu/date-to-timestamp (later (tu/timestamp-to-date (get x k)) (get y k))))
          (if-assoc [m f k x ^Pgm y]
            (let [vx (get x k) v (f k x y)]
              (if (not= vx v) (assoc m k v) m)))]
    (-> {}
        (if-assoc longer-for :title       row-pgm pgm)
        (if-assoc later-for  :pubdate     row-pgm pgm)
        (if-assoc longer-for :description row-pgm pgm)
        (assoc :updated_at (tu/sql-now)))))

(defn- pgm-to-row [^Pgm pgm]
  {:id (name (:id pgm))
   :title (s/trim-to (:title pgm) LEN_PGM_TITLE)
   :pubdate (tu/date-to-timestamp (:pubdate pgm))
   :description (s/trim-to (:desc pgm) LEN_PGM_DESCRIPTION)
   :category (:category pgm)
   :link (:link pgm)
   :owner_name (s/trim-to (:owner_name pgm) LEN_PGM_OWNER_NAME)
   :member_only (:member_only pgm)
   :type (condp = (:type pgm) :community 0 :channel 1 :official 2)
   :comm_id (if-let [cid (:comm_id pgm)] (name cid) nil)
   :alerted (:alerted pgm)
   :fetched_at (tu/date-to-timestamp (:fetched_at pgm))
   :updated_at (tu/date-to-timestamp (:updated_at pgm))})


(let [q (LinkedBlockingQueue.)
      last-updated (atom (tu/now))
      last-cleaned (atom (tu/now))
      pool (proxy [ThreadPoolExecutor] [1 NTHREADS KEEP-ALIVE TimeUnit/SECONDS q]
             (afterExecute
               [r e]
               (reset! last-updated (tu/now))
               (trace (format "added pgm (%d / %d)" (.getActiveCount this) (.size q)))
               (when (= 0 (.size q))
                 (future
                   (.sleep TimeUnit/SECONDS INTERVAL-UPDATE)
                   (call-hook-updated)))))]
  (defn adding-queue-size [] (.size q))
  (defn last-updated [] @last-updated)
  (letfn [(clean-old2 [num]
            (let [start (Timestamp. (.getTimeInMillis (doto (Calendar/getInstance) (.add Calendar/MINUTE -30))))]
              (jdbc/transaction
               (jdbc/delete-rows :pgms ["id IN (SELECT TOP ? id FROM pgms WHERE pubdate < ? ORDER BY updated_at)"
                                        num start]))
              (reset! last-cleaned (tu/now))))
          (clean-old1 []
            (try
              (with-conn-pool db nil
                (when-not (tu/within? @last-cleaned (tu/now) INTERVAL-CLEAN)
                  (let [total (get-total)
                        cnt (count-pgms-aux)
                        threshold (int (* SCALE total))] ;; 総番組数のscale倍までは許容
                    (when (and (< 0 total) (< threshold cnt))
                      ;; 更新時刻の古い順に多すぎる番組を削除する。
                      (debug (format "cleaning old: %d -> %d" cnt threshold))
                      (clean-old2 (- cnt threshold))
                      (debug (format "cleaned old: %d -> %d" cnt (count-pgms-aux)))))
                  (call-hook-updated)))
              (catch Exception e
                (error e (format "failed cleaning old programs: %s" (.getMessage e))))))]
    (defn clean-old [] (.execute pool #(clean-old1))))

  (letfn [(add3 [^Pgm pgm]
            (let [comm_id (if-let [cid (:comm_id pgm)] (name cid) nil)
                  row-comm (if comm_id (get-row-comm comm_id) nil)
                  now (tu/sql-now)]
              (trace (format "add pgm: %s" (name (:id pgm))))
              (try ; 番組情報を追加する
                (let [row (pgm-to-row pgm)]
                  (jdbc/insert-record :pgms row))
                (catch Exception e (error e (format "failed to insert pgm: %s" (prn-str pgm)))))
              (try ; コミュニティ情報を更新または追加する
                (if row-comm
                  (jdbc/update-values :comms ["id=?" comm_id]
                                      {:comm_name (s/trim-to (:comm_name pgm) LEN_COMM_NAME)
                                       :thumbnail (:thumbnail pgm)
                                       :updated_at now})
                  (when comm_id
                    (jdbc/insert-record :comms
                                        {:id comm_id
                                         :comm_name (s/trim-to (:comm_name pgm) LEN_COMM_NAME)
                                         :thumbnail (:thumbnail pgm)
                                         :fetched_at now :updated_at now})))
                (catch Exception e (error e "failed updating or inserting comm info")))))
          (add2 [^Pgm pgm]
            (let [pid (name (:id pgm))
                  row-pgm (get-pgm-aux pid)
                  comm_id (if-let [cid (:comm_id pgm)] (name cid) nil)
                  row-comm-pgm (if comm_id (get-row-pgm-by-comm-id comm_id) nil)]
              (if row-pgm
                (jdbc/transaction ; 番組情報が既にある場合は更新する
                 (let [diff-pgm (merge-pgm row-pgm pgm)]
                   (trace (format "update pgm (%s) with %s" pid (pr-str diff-pgm)))
                   (jdbc/update-values :pgms ["id=?" pid] diff-pgm)))
                (if row-comm-pgm ; 同じコミュニティの番組があったらどちらが古いかを確認する
                  (if (tu/later? (:pubdate pgm) (tu/timestamp-to-date (:pubdate row-comm-pgm)))
                    (jdbc/transaction ; 自分のほうが新しければ古いのを削除してから追加する
                     (rem-pgm-by-id (:id row-comm-pgm))
                     (add3 pgm))
                    nil) ; 自分のほうが古ければ追加は不要
                  (jdbc/transaction (add3 pgm))))))
          (add1 [^Pgm pgm]
            (try
              (with-conn-pool db nil
                (add2 pgm)
                (call-hook-updated))
              (catch Exception e
                (error e (format "failed adding program (%s) %s" (name (:id pgm)) (:title pgm))))))]
    (defn add [^Pgm pgm] (.execute pool #(add1 pgm)))))

(defn- rs-to-pgms [rs]
  (let [pgms (atom {})]
    (doseq [r rs]
      (let [p (row-to-pgm r)]
        (swap! pgms assoc (:id p) p)))
    @pgms))

(defn get-sql-comm-id [comm_ids]
  (let [in-clause (apply str (interpose "," (for [comm_id comm_ids] (str \' comm_id \'))))]
    (debug (format "in-clause: %s" in-clause))
    (str "SELECT * FROM pgms WHERE comm_id IN (" in-clause ")")))

(defn get-sql-kwds [query targets]
  (let [s (set targets)
        s2 (if (contains? s :desc) (-> s (disj :desc) (conj :description)) s)
        s3 (map name s2)
        where-clause (q/to-where-clause query s3)]
    (debug (format "where-clause: %s" where-clause))
    (str "SELECT * FROM pgms JOIN comms ON pgms.comm_id=comms.id WHERE " where-clause)))

(defn- search-pgms-by-sql [sql]
  (with-conn-pool db nil
    (jdbc/with-query-results rs [{:concurrency :read-only} sql]
      (rs-to-pgms rs))))

(defn search-pgms-by-comm-id [comm_ids]
  (search-pgms-by-sql (get-sql-comm-id comm_ids)))

(defn search-pgms-by-keywords [query targets]
  (search-pgms-by-sql (get-sql-kwds query targets)))

(defn search-pgms-by-pstmt [pstmt]
  (if-not (.isClosed pstmt)
    (let [rs (.executeQuery pstmt)]
      (try
        (rs-to-pgms (jdbc/resultset-seq rs))
        (catch Exception e (error e (format "failed rs-to-pgms: %s" pstmt)))
        (finally (.close rs))))
    {}))
