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
           [java.lang.management ManagementFactory]
           [java.sql Connection DriverManager SQLException Timestamp]
           [java.util Calendar]
           [java.util.concurrent Callable LinkedBlockingQueue ThreadPoolExecutor TimeUnit]
           [javax.swing ImageIcon]))

(def ^{:private true} KEEP-ALIVE 5)       ;; ワーカースレッド待機時間

(def ^{:private true} SCALE 1.05) ;; 番組最大保持数
(def ^{:private true} INTERVAL-CLEAN 180) ;; 古い番組情報を削除する間隔
(def ^{:private true} INTERVAL-UPDATE 3) ;; 更新フック呼び出し間隔

(def ^{:private true} DB-CLASSNAME "org.sqlite.JDBC")

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

(defn- create-db [db-spec]
  (jdbc/with-connection db-spec
    (jdbc/do-commands "PRAGMA auto_vacuum=2")
    (jdbc/do-commands "PRAGMA incremental_vacuum(64)")
    (create-pgms)
    (create-comms)))

(defn- delete-db-file [path] (io/delete-all-files path))

(let [db-path (io/temp-file-name "nico-" ".db")
      db (atom {:classname DB-CLASSNAME
                :subprotocol "sqlite"
                :subname db-path})]
  (hu/defhook db :shutdown)
  (defn init []
    (if (io/delete-all-files db-path)
      (l/with-info (format "initialize Database to %s" db-path)
        (create-db @db)
        (swap! db assoc :connection (DriverManager/getConnection (format "jdbc:sqlite:%s" db-path)))
        true)
      (l/with-error (format "failed initializing Database")
        false)))
  (defn shutdown []
    (run-db-hooks :shutdown)
    (when-let [conn (:connection @db)]
      (try
        (.close conn)
        (catch Exception e
          (error e (format "failed closing conn: %s" (pr-str conn))))
        (finally
          (swap! db dissoc :connection))))
    (.sleep TimeUnit/SECONDS 1)
    (delete-db-file db-path))
  (defn- db [] (deref db))
  (defn get-conn [] (:connection @db)))

(defmacro ^{:private true} with-pooled-conn [no-conn-value & body]
  `(if-let [conn# (:connection (db))]
     (locking conn#
       (jdbc/with-connection (db) ~@body))
     ~no-conn-value))

(let [called_at (atom (tu/now))]
  (hu/defhook pgms :updated)
  (defn- call-hook-updated []
    (let [now (tu/now)]
      (when-not (tu/within? @called_at now INTERVAL-UPDATE)
        (run-pgms-hooks :updated)
        (reset! called_at now)))))

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
  (if-let [raw-row (with-pooled-conn nil
                     (jdbc/with-query-results rs
                       [{:concurrency :read-only} "SELECT * FROM comms WHERE id=?" comm_id]
                       (first rs)))]
    (tu/update-timestamp-sqlite raw-row [:fetched_at :updated_at])
    nil))

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
     (condp = (:member_only row-pgm) 0 false 1 true)
     (condp = (:type row-pgm) 0 :community 1 :channel 2 :official)
     (:comm_name row-comm)
     (keyword (:comm_id row-pgm))
     (:alerted row-pgm)
     (tu/timestamp-to-date (:fetched_at row-pgm))
     (tu/timestamp-to-date (:updated_at row-pgm)))))

(defn- count-pgms-aux []
  (jdbc/with-query-results rs [{:concurrency :read-only} "SELECT COUNT(*) AS cnt FROM pgms"]
    (:cnt (first rs))))

(defn count-pgms [] (with-pooled-conn -1 (count-pgms-aux)))

(defn- get-pgm-aux [^String id]
  (if-let [raw-row (jdbc/with-query-results rs
                     [{:concurrency :read-only} "SELECT * FROM pgms WHERE pgms.id=?" id]
                     (first rs))]
    (tu/update-timestamp-sqlite raw-row [:pubdate :fetched_at :updated_at])
    nil))

(defn get-pgm [^Keyword id]
  (with-pooled-conn nil
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
    (with-pooled-conn nil
      (if (update-alerted-aux (name id))
        (if-let [row-pgm (get-pgm-aux (name id))]
          (row-to-pgm row-pgm)
          (error (format "failed get-pgm: %s" (name id))))
        nil))))

(defn- rem-pgm-by-id [^String pid]
  (jdbc/delete-rows :pgms ["id=?" pid]))

(defn- get-row-pgm-by-comm-id [^String comm_id]
  (if-let [raw-row (jdbc/with-query-results rs
                     [{:concurrency :read-only} "SELECT * FROM pgms WHERE comm_id=?" comm_id]
                     (first rs))]
    (tu/update-timestamp-sqlite raw-row [:pubdate :fetched_at :updated_at])
    nil))

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
      pool (proxy [ThreadPoolExecutor] [1 1 KEEP-ALIVE TimeUnit/SECONDS q]
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
;;            (let [start (Timestamp. (.getTimeInMillis (doto (Calendar/getInstance) (.add Calendar/MINUTE -30))))]
            ;; sqliteはtimestamp値をミリ秒longで保持するのでこの値でそのまま比較できるはず。
            (let [start (.getTimeInMillis (doto (Calendar/getInstance) (.add Calendar/MINUTE -30)))]
              (jdbc/transaction
               (jdbc/delete-rows :pgms ["id IN (SELECT TOP ? id FROM pgms WHERE pubdate < ? ORDER BY updated_at)"
                                        num start]))
              (reset! last-cleaned (tu/now))))
          (clean-old1 []
            (try
              (with-pooled-conn nil
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
          ;; add2はadd1とadd1-pgms双方から呼ばれる。違いはadd1が一つずつ追加するのに対し、
          ;; add1-pgmsは複数を一度で追加する。add1から呼ばれた場合はadd2の中のトランザクションが有効となる。
          ;; add1-pgmsから呼ばれた場合は外側(add1-pgms内)でくくられたトランザクションが有効となる。
          ;; jdbc/transactionはネストされると外側のトランザクションのみが有効となることを利用している。
          (add1 [^Pgm pgm]
            (try
              (with-pooled-conn nil
                (add2 pgm)
                (call-hook-updated))
              (catch Exception e
                (error e (format "failed adding program (%s) %s" (name (:id pgm)) (:title pgm))))))
          (add1-pgms [pgms]
            (try
              (with-pooled-conn nil
                (jdbc/transaction
                 (doseq [pgm pgms]
                   (add2 pgm)
                   (call-hook-updated))))
              (catch Exception e
                (error e (format "failed adding programs: [%s]" (pr-str pgms))))))]
    (defn add [^Pgm pgm] (.execute pool #(add1 pgm)))
    (defn add-pgms [pgms] (.execute pool #(add1-pgms pgms)))))

(defn- rs-to-pgms [rs]
  (let [pgms (atom {})]
    (doseq [r rs]
      (let [p (-> r (tu/update-timestamp-sqlite [:pubdate :fetched_at :updated_at]) row-to-pgm)]
        (trace (format "raw-row: %s => %s" (pr-str r) (pr-str p)))
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
  (with-pooled-conn nil
    (jdbc/with-query-results rs [{:concurrency :read-only} sql]
      (rs-to-pgms rs))))

(defn search-pgms-by-comm-id [comm_ids]
  (search-pgms-by-sql (get-sql-comm-id comm_ids)))

(defn search-pgms-by-keywords [query targets]
  (search-pgms-by-sql (get-sql-kwds query targets)))

(defn search-pgms-by-pstmt [pstmt]
  (if pstmt
    (locking (.getConnection pstmt)
      (let [rs (.executeQuery pstmt)]
        (try
          (rs-to-pgms (jdbc/resultset-seq rs))
          (catch Exception e (error e (format "failed rs-to-pgms: %s" pstmt)))
          (finally (.close rs)))))
    {}))
