;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコニコ生放送の番組情報とその操作"}
  nico.pgm
  (:use [clojure.tools.logging])
  (:require [hook-utils :as hu]
            [io-utils :as io]
            [log-utils :as l]
            [net-utils :as n]
            [query-utils :as q]
            [str-utils :as s]
	    [time-utils :as tu]
            [clojure.java.jdbc :as jdbc])
  (:import [clojure.lang Keyword]
           [javax.imageio ImageIO]
           [java.io ByteArrayInputStream File OutputStream]
           [java.sql Connection DriverManager SQLException Timestamp]
           [java.util.concurrent Callable LinkedBlockingQueue ThreadPoolExecutor TimeUnit]
           [com.mchange.v2.c3p0 ComboPooledDataSource]))

(def ^{:private true} NTHREADS 3) ;; 番組追加ワーカースレッド数
(def ^{:private true} KEEP-ALIVE 5)       ;; ワーカースレッド待機時間

(def ^{:private true} SCALE 1.14) ;; 番組最大保持数
(def ^{:private true} INTERVAL-CLEAN 180) ;; 古い番組情報を削除する間隔
(def ^{:private true} INTERVAL-UPDATE 3) ;; 更新フック呼び出し間隔
(def ^{:private true} INTERVAL-UPDATE-THUMBNAIL (* 24 3600)) ; サムネイル更新間隔

(def ^{:private true} NO-IMAGE (ImageIO/read (.getResource (.getClassLoader (class (fn []))) "noimage.png")))

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

(defn- create-imgs []
  (jdbc/create-table
   :imgs
   [:id (varchar LEN_COMM_ID) "PRIMARY KEY"]
   [:img "blob(100K)"]
   [:fetched_at :timestamp]
   [:updated_at :timestamp])
  (jdbc/do-commands "CREATE INDEX idx_imgs_id ON imgs(id)"
                    "CREATE INDEX idx_imgs_updated_at ON imgs(updated_at)"))

(def ^{:private true} LEN_PGM_ID          10)
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
   [:member_only :smallint] ;; 1: true 0: false
   [:type :smallint] ;; 0: community 1: channel 2: official
   [:comm_id (varchar LEN_COMM_ID)]
   [:alerted :smallint] ;; 1: true 0: false
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

(defn- create-pmeta []
  (jdbc/create-table
   :pmeta
   [:id :integer "GENERATED ALWAYS AS IDENTITY"]
   [:total :integer] ; 総番組数
   [:last_cleaned_at :timestamp]))      ; 番組情報の最終クリーンアップ時刻

(defn- init-db-aux [path]
  (let [db {:classname DB-CLASSNAME
            :subprotocol "h2"
            :subname (str "file:" path)
            :create true}]
    (jdbc/with-connection db
      (create-pmeta)
      (jdbc/insert-values :pmeta [:total :last_cleaned_at] [0 (tu/sql-now)])
      (create-pgms)
      (create-comms)
      (create-imgs))))

(defn- pool [path]
  (let [cpds (doto (ComboPooledDataSource.)
               (.setDriverClass DB-CLASSNAME)
               (.setJdbcUrl (str "jdbc:h2:file:" path))
               (.setMaxIdleTimeExcessConnections (* 2 60))
               (.setMaxIdleTime 60))]
    {:datasource cpds}))

(defn- delete-db-files [path]
  (let [db-file           (str path ".h2.db")
        db-trace-file     (str path ".trace.db")
        db-trace-old-file (str path ".trace.db.old")
        db-lobs-file      (str path ".lobs.db")
        db-lock-file      (str path ".lock.db")]
    (doseq [f [db-file db-trace-file db-trace-old-file db-lobs-file db-lock-file]]
      (io/delete-all-files f))))

(let [db-path (io/temp-file-name "nico-")
      ro-conns (atom [])
      pooled-db (atom nil)]
  (hu/defhook db :shutdown)
  (defn init-db []
    (io/delete-all-files db-path)
    (init-db-aux db-path)
    (reset! pooled-db (pool db-path)))
  (defn shutdown []
    (run-db-hooks :shutdown)
    (let [d @pooled-db]
      (reset! pooled-db nil)
      (doseq [ro-conn @ro-conns] (.close ro-conn))
      (doto (:datasource d)
        (.setMinPoolSize 0)
        (.setInitialPoolSize 0)
        (.setMaxIdleTime 1))
      (.sleep TimeUnit/SECONDS 3)
      (.close (:datasource d))
      (delete-db-files db-path)))
  (defn- db [] @pooled-db)
  (defn get-ro-conn []
    (let [ro-conn (doto (DriverManager/getConnection (format "jdbc:h2:file:%s" db-path))
                    (.setReadOnly true))]
      (swap! ro-conns conj ro-conn)
      ro-conn)))

(defn- get-pmeta [^Keyword k]
  (jdbc/with-query-results rs [{:concurrency :read-only} "SELECT * FROM pmeta WHERE id=?" 1]
    (get (first rs) k)))

(let [called-at-hook-updated (ref (tu/now))] ;; フックを呼び出した最終時刻
  (hu/defhook pgms :updated)
  (defn- call-hook-updated []
    (dosync
     (when-not (tu/within? @called-at-hook-updated (tu/now) INTERVAL-UPDATE)
       (run-pgms-hooks :updated)
       (ref-set called-at-hook-updated (tu/now))))))

(let [old-total (atom -1)] ; DBへの問い合わせを抑制するため
  (defn set-total [total]
    (when (not= @old-total total)
      (when-let [db (db)]
        (jdbc/with-connection db
          (trace (format "set-total: %d -> %d" @old-total total))
          (jdbc/transaction
           (jdbc/update-values :pmeta ["id=?" 1] {:total total}))
          (reset! old-total total)))
      (call-hook-updated))))

(defn- get-row-comm [^String comm_id]
  (if-let [db (db)]
    (jdbc/with-connection db
      (jdbc/with-query-results
        rs [{:concurrency :read-only}
            "SELECT id,comm_name,thumbnail,fetched_at,updated_at FROM comms WHERE id=?" comm_id]
        (first rs)))
    nil))


(defn get-comm-thumbnail [^Keyword comm_id]
  (letfn [(store-thumbnail [comm_id img update]
            (when-let [db (db)]
              (jdbc/with-connection db
                (let [now (tu/sql-now)]
                  (if update
                    (jdbc/update-values
                     :imgs ["=id?" comm_id] {:img img :updated_at now})
                    (jdbc/insert-record
                     :imgs {:id comm_id :img img :fetched_at now :updated_at now}))))))
          (fetch-image-aux [url]
            (try
              (let [is (n/url-stream url)]
                (try (io/input-stream-to-bytes is)
                     (finally (when is (.close is)))))
              (catch Exception e
                (warn (format "failed open inputstream from %s: %s" url (.getMessage e)))
                nil)))
          (fetch-image [comm_id update]
            (if-let [row-comm (get-row-comm comm_id)]
              (let [url (:thumbnail row-comm)
                    img (fetch-image-aux url)
                    bis (ByteArrayInputStream. img)]
                (future (store-thumbnail comm_id img update))
                (try (ImageIO/read bis)
                     (catch Exception e (error e (format "failed reading image: %s" url)) NO-IMAGE)
                     (finally (.close bis))))
              NO-IMAGE))]
    (if-let [db (db)]
      (jdbc/with-connection db
        (jdbc/with-query-results rs [{:concurrency :read-only}
                                     "SELECT img,updated_at FROM imgs WHERE id=?" (name comm_id)]
          (if-let [r (first rs)]
            (if (tu/within? (tu/timestamp-to-date (:updated_at r)) (tu/now) INTERVAL-UPDATE-THUMBNAIL)
              (let [blob (:img r)]
                (if (and blob (< 0 (.length blob)))
                  (let [bs (.getBinaryStream (:img r))]
                    (try (ImageIO/read bs)
                         (finally (.close bs) (.free blob))))
                  NO-IMAGE))
              (fetch-image (name comm_id) true))
            (fetch-image (name comm_id) false))))
      NO-IMAGE)))

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
     (if (= 1 (:member_only row-pgm)) true false)
     (condp = (:type row-pgm) 0 :community 1 :channel 2 :official)
     (:comm_name row-comm)
     (keyword (:comm_id row-pgm))
     (if (= 1 (:alerted row-pgm)) true false)
     (tu/timestamp-to-date (:fetched_at row-pgm))
     (tu/timestamp-to-date (:updated_at row-pgm)))))

(defn- count-pgms-aux []
  (jdbc/with-query-results rs [{:concurrency :read-only} "SELECT COUNT(*) AS cnt FROM pgms"]
    (:cnt (first rs))))

(defn count-pgms []
  (if-let [db (db)]
    (jdbc/with-connection db
      (count-pgms-aux))
    -1))

(defn- get-pgm-aux [^String id]
  (jdbc/with-query-results rs [{:concurrency :read-only} "SELECT * FROM pgms WHERE pgms.id=?" id]
    (first rs)))

(defn get-pgm [^Keyword id]
  (if-let [db (db)]
    (jdbc/with-connection db
      (if-let [row-pgm (get-pgm-aux (name id))] (row-to-pgm row-pgm) nil))
    nil))

(defn not-alerted
  "まだアラートを出してない番組なら番組情報を返す。アラート済みならnilを返す。"
  [^Keyword id]
  (letfn [(update-alerted-aux [^String id]
            (try
              (jdbc/transaction
               (let [result (jdbc/update-values :pgms ["id=? AND alerted=0" (name id)] {:alerted 1})]
                 (if (= 1 (first result)) true false)))
              (catch Exception e
                (debug e (format "failed updating for " id))
                false)))]
    (if-let [db (db)]
      (jdbc/with-connection db
        (if (update-alerted-aux (name id))
          (if-let [row-pgm (get-pgm-aux (name id))]
            (row-to-pgm row-pgm)
            (error (format "failed get-pgm: %s" (name id))))
          nil))
      nil)))

(defn get-total []
  (if-let [db (db)]
    (jdbc/with-connection db
      (get-pmeta :total))
    -1))

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
   :member_only (if (:member_only pgm) 1 0)
   :type (condp = (:type pgm) :community 0 :channel 1 :official 2)
   :comm_id (if-let [cid (:comm_id pgm)] (name cid) nil)
   :alerted (if (:alerted pgm) 1 0)
   :fetched_at (tu/date-to-timestamp (:fetched_at pgm))
   :updated_at (tu/date-to-timestamp (:updated_at pgm))})


(let [q (LinkedBlockingQueue.)
      last-updated (atom (tu/now))
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
            ;; 古い番組情報を削除する。derbyではサブクエリーが遅いので、自前で処理した方が速い。
            (let [ids (jdbc/with-query-results rs [{:concurrency :read-only :max-rows num }
                                                   "SELECT id FROM pgms ORDER BY updated_at"]
                        (let [ids (atom [])]
                          (doseq [r rs]
                            (let [id (str \' (:id r) \')]
                              (swap! ids conj id)))
                          @ids))
                  where-clause (str "id IN (" (apply str (interpose "," ids)) ")")]
              (debug (format "cleaning old programs with WHERE-CLAUSE: '%s'" where-clause))
              (jdbc/transaction
               (jdbc/delete-rows :pgms [where-clause])
               (jdbc/update-values :pmeta ["id=?" 1] {:last_cleaned_at (tu/sql-now)}))))
          (clean-old1 []
            (when-let [db (db)]
              (try
                (jdbc/with-connection db
                  (when-not (tu/within? (tu/timestamp-to-date (get-pmeta :last_cleaned_at)) (tu/now) INTERVAL-CLEAN)
                    (let [total (get-pmeta :total)
                          cnt (count-pgms-aux)
                          threshold (int (* SCALE total))] ;; 総番組数のscale倍までは許容
                      (when (and (< 0 total) (< threshold cnt))
                        ;; 更新時刻の古い順に多すぎる番組を削除する。
                        (debug (format "cleaning old: %d -> %d" cnt threshold))
                        (clean-old2 (- cnt threshold))
                        (debug (format "cleaned old: %d -> %d" cnt (count-pgms-aux)))))))
                (catch Exception e
                  (error e (format "failed cleaning old programs: %s" (.getMessage e)))))
              (call-hook-updated)))]
    (defn clean-old [] (.execute pool #(clean-old1))))

  (letfn [(add3 [^Pgm pgm]
            (let [comm_id (if-let [cid (:comm_id pgm)] (name cid) nil)
                  row-comm (if comm_id (get-row-comm comm_id) nil)
                  now (tu/sql-now)]
              (trace (format "add pgm: %s" (name (:id pgm))))
              ;; 番組情報を追加する
              (try
                (let [row (pgm-to-row pgm)]
                  (jdbc/insert-record :pgms row))
                (catch Exception e (error e (format "failed to insert pgm: %s" (prn-str pgm)))))
              ;; コミュニティ情報を更新または追加する
              (try
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
            (when-let [db (db)]
              (try
                (jdbc/with-connection db
                  (add2 pgm))
                (catch Exception e
                  (error e (format "failed adding program (%s) %s" (name (:id pgm)) (:title pgm)))))
              (call-hook-updated)))]
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
  (if-let [db (db)]
    (jdbc/with-connection db
      (jdbc/with-query-results rs [{:concurrency :read-only} sql]
        (rs-to-pgms rs)))
    nil))

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
