;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコニコ生放送の番組情報とその操作"}
  nico.pgm
  (:use [clojure.tools.logging])
  (:require [hook-utils :as hu]
            [io-utils :as io]
            [log-utils :as l]
            [net-utils :as n]
            [str-utils :as s]
	    [time-utils :as tu]
            [clojure.java.jdbc :as jdbc])
  (:import [clojure.lang Keyword]
           [javax.imageio ImageIO]
           [java.io File]
           [java.sql Connection DriverManager SQLException Timestamp]
           [java.util.concurrent Callable CountDownLatch LinkedBlockingQueue ThreadPoolExecutor TimeUnit]
           [com.mchange.v2.c3p0 ComboPooledDataSource]))

(def ^{:private true} NTHREADS 10) ;; 番組追加ワーカースレッド数
(def ^{:private true} KEEP-ALIVE 5)       ;; ワーカースレッド待機時間

(def ^{:private true} SCALE 1.1) ;; 番組最大保持数
(def ^{:private true} INTERVAL-CLEAN 180) ;; 古い番組情報を削除する間隔
(def ^{:private true} INTERVAL-UPDATE 3) ;; 更新フック呼び出し間隔

(def ^{:private true} NO-IMAGE (ImageIO/read (.getResource (.getClassLoader (class (fn []))) "noimage.png")))

(def ^{:private true} DB-CLASSNAME "org.apache.derby.jdbc.EmbeddedDriver")

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
   [:thumbnail_img "blob(100K)"]
   [:fetched_at :timestamp]
   [:updated_at :timestamp])
  (jdbc/do-commands "CREATE INDEX idx_comms_id ON comms(id)"
                    "CREATE INDEX idx_comms_name ON comms(comm_name)"
                    "CREATE INDEX idx_comms_updated_at ON comms(updated_at)"))

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
            :subprotocol "derby"
            :subname path
            :create true}]
    (jdbc/with-connection db
      (create-pmeta)
      (jdbc/insert-values :pmeta [:total :last_cleaned_at] [0 (tu/sql-now)])
      (create-pgms)
      (create-comms))))

(defn- pool [path]
  (let [cpds (doto (ComboPooledDataSource.)
               (.setDriverClass DB-CLASSNAME)
               (.setJdbcUrl (str "jdbc:derby:" path))
               (.setMaxIdleTimeExcessConnections (* 2 60))
               (.setMaxIdleTime 60))]
    {:datasource cpds}))

(defn- shutdown-db [path]
  (try
    (DriverManager/getConnection (format "jdbc:derby:%s;shutdown=true" path))
    (catch Exception e
      (debug (format "shutdown derby: %s" (.getMessage e)))
      (io/delete-all-files path))))

(let [db-path (.getCanonicalPath (File/createTempFile "nico-" nil))
      pooled-db (atom nil)]
  (defn init-db []
    (io/delete-all-files db-path)
    (init-db-aux db-path)
    (reset! pooled-db (pool db-path)))
  (defn shutdown []
    (let [d @pooled-db]
      (reset! pooled-db nil)
      (.close (:datasource d))
      (shutdown-db db-path)))
  (defn- db [] @pooled-db))

(defn- drop-tbls []
  (doseq [tbl [:pmeta :pgms :comms]] (jdbc/drop-table tbl)))

(defn- get-pmeta [^Keyword k]
  (jdbc/with-query-results rs ["SELECT * FROM pmeta WHERE id=?" 1]
    (get (first rs) k)))

(let [called-at-hook-updated (ref (tu/now))] ;; フックを呼び出した最終時刻
  (hu/defhook :updated)
  (defn- call-hook-updated []
    (dosync
     (when-not (tu/within? @called-at-hook-updated (tu/now) INTERVAL-UPDATE)
       (run-hooks :updated)
       (ref-set called-at-hook-updated (tu/now))))))

(let [old-total (atom -1)] ; DBへの問い合わせを抑制するため
  (defn set-total [total]
    (when (not= @old-total total)
      (jdbc/with-connection (db)
        (trace (format "set-total: %d -> %d" @old-total total))
        (jdbc/transaction
         (jdbc/update-values :pmeta ["id=?" 1] {:total total}))
        (reset! old-total total))
      (call-hook-updated))))

;; 以下はリファクタリング用に同じ物をとりあえず実装する。
;; 動くようになったら徐々に不要なメソッドをなくしてメモリ効率を良くしていく
(defn- get-row-comm [^String comm_id]
  (jdbc/with-query-results rs ["SELECT * FROM comms WHERE id=?" comm_id]
    (first rs)))

(defn get-comm-thumbnail [^Keyword comm_id]
  (jdbc/with-connection (db)
    (jdbc/with-query-results rs ["SELECT * FROM comms WHERE id=?" (name comm_id)]
      (if-let [r (first rs)]
        (let [blob (:thumbnail_img r)]
          (if (or (nil? blob) (= 0 (.length blob)))
            NO-IMAGE
            (ImageIO/read (.getBinaryStream (:thumbnail_img r)))))
        NO-IMAGE))))

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

(defn pgms []
  (jdbc/with-connection (db)
    (jdbc/with-query-results rs ["SELECT * FROM pgms"]
      (let [pgms (atom {})]
        (doseq [r rs]
          (let [p (row-to-pgm r)]
            (swap! pgms assoc (:id p) p)))
        @pgms))))

(defn- count-pgms-aux []
  (jdbc/with-query-results rs ["SELECT COUNT(*) AS cnt FROM pgms"]
    (:cnt (first rs))))

(defn count-pgms []
  (jdbc/with-connection (db)
    (count-pgms-aux)))

(defn- get-pgm-aux [^String id]
  (jdbc/with-query-results rs ["SELECT * FROM pgms WHERE pgms.id=?" id]
    (first rs)))

(defn get-pgm [^Keyword id]
  (jdbc/with-connection (db)
    (if-let [row-pgm (get-pgm-aux (name id))] (row-to-pgm row-pgm) nil)))

(defn update-alerted [^Keyword id]
  (jdbc/with-connection (db)
    (jdbc/transaction
     (jdbc/update-values :pgms ["id=?" (name id)] {:alerted true}))))

(defn get-total []
  (jdbc/with-connection (db)
    (get-pmeta :total)))

(defn- rem-pgm-by-id [^String pid]
  (jdbc/delete-rows :pgms ["id=?" pid]))

(defn- get-row-pgm-by-comm-id [^String comm_id]
  (jdbc/with-query-results rs ["SELECT * FROM pgms WHERE comm_id=?" comm_id]
    (first rs)))

(defn- merge-pgm [row-pgm ^Pgm pgm]
  (letfn [(longer [^String x ^String y]
            (cond (and x y) (if (> (.length x) (.length y)) x y)
                  (nil? x) y
                  (nil? y) x
                  :else nil))
          (longer-for [k x ^Pgm y]
            (longer (get x k)
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

(defn- fetch-image [url]
  (try
    (io/input-stream-to-bytes (n/url-stream url))
    (catch Exception e
      (warn (format "failed open inputstream from %s: %s" url (.getMessage e)))
      nil)))

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

(defn- add-aux [^Pgm pgm]
  (let [pid (name (:id pgm))
        comm_id (if-let [cid (:comm_id pgm)] (name cid) nil)
        row-pgm (get-pgm-aux pid)
        row-comm-pgm (if comm_id (get-row-pgm-by-comm-id comm_id) nil)
        row-comm (if comm_id (get-row-comm comm_id) nil)]
    (if row-pgm
      (jdbc/transaction ; 番組情報が既にある場合は更新する
       (let [diff-pgm (merge-pgm row-pgm pgm)]
         (trace (format "update pgm (%s) with %s" pid (pr-str diff-pgm)))
         (jdbc/update-values :pgms ["id=?" pid] diff-pgm)))
      (do ; 番組情報がない場合は登録する
        (when (and row-comm-pgm ; 同じコミュニティの古い番組があったら削除する
                   (tu/later? (:pubdate pgm) (tu/timestamp-to-date (:pubdate row-comm-pgm))))
          (jdbc/transaction (rem-pgm-by-id (:id row-comm-pgm))))
        (jdbc/transaction
         (trace (format "add pgm: %s" pid))
         ;; 番組情報を追加する
         (let [row (pgm-to-row pgm)] (jdbc/insert-values :pgms (keys row) (vals row)))
         ;; コミュニティ情報を更新または追加する
         (if row-comm
           (when (tu/within? (tu/timestamp-to-date (:updated_at row-comm)) (tu/now) 7200)
             (jdbc/update-values :comms ["id=?" comm_id]
                                 {:comm_name (s/trim-to (:comm_name pgm) LEN_COMM_NAME)
                                  :thumbnail_img (fetch-image (:thumbnail pgm))
                                  :thumbnail (:thumbnail pgm)
                                  :updated_at (tu/sql-now)}))
           (when comm_id
             (jdbc/insert-values :comms
                                 [:id :comm_name :thumbnail_img :thumbnail :fetched_at :updated_at]
                                 [comm_id (s/trim-to (:comm_name pgm) LEN_COMM_NAME)
                                  (fetch-image (:thumbnail pgm))
                                  (:thumbnail pgm) (tu/sql-now) (tu/sql-now)]))))))))

(defn- add1 [^Pgm pgm]
  (try
    (jdbc/with-connection (db)
      (add-aux pgm))
    (catch Exception e
      (error e (format "failed adding program (%s) %s" (name (:id pgm)) (:title pgm)))))
  (call-hook-updated))

(defn- clean-old-aux-old
  "古い番組情報を削除する。derbyではサブクエリーが少々ややこしくなる上に、これは非常に遅い。"
  [num]
  (jdbc/transaction
   (jdbc/delete-rows
    :pgms
    ["id IN (SELECT id FROM (SELECT ROW_NUMBER() OVER() AS r, pgms.id FROM pgms ORDER BY updated_at) AS tmp WHERE r <= ?)" num])
   (jdbc/update-values :pmeta ["id=?" 1] {:last_cleaned_at (tu/sql-now)})))

(defn- clean-old-aux
  "古い番組情報を削除する。derbyではサブクエリーが遅いので、自前で処理。こちらの方が速い。"
  [num]
  (let [ids (jdbc/with-query-results rs ["SELECT id FROM pgms ORDER BY updated_at"]
              (loop [ids [], rs rs]
                (if (= num (count ids))
                  ids
                  (recur (conj ids (str \'(:id (first rs)) \')) (rest rs)))))
        where-clause (str "id IN (" (apply str (interpose "," ids)) ")")]
    (trace (format "cleaning old programs with WHERE-CLAUSE: '%s'" where-clause))
    (jdbc/transaction
     (jdbc/delete-rows :pgms [where-clause])
     (jdbc/update-values :pmeta ["id=?" 1] {:last_cleaned_at (tu/sql-now)}))))

(defn- clean-old1 []
  (try
    (jdbc/with-connection (db)
      (when-not (tu/within? (tu/timestamp-to-date (get-pmeta :last_cleaned_at)) (tu/now) INTERVAL-CLEAN)
        (let [total (get-pmeta :total)
              cnt (count-pgms-aux)
              threshold (int (* SCALE total))] ;; 総番組数のscale倍までは許容
          (when (and (< 0 total) (< threshold cnt))
            ;; 更新時刻の古い順に多すぎる番組を削除する。
            (debug (format "cleaning old: %d -> %d" cnt threshold))
            (clean-old-aux (- cnt threshold))
            (debug (format "cleaned old: %d -> %d" cnt (count-pgms-aux)))))))
    (catch Exception e
      (error e (format "failed cleaning old programs: %s" (.getMessage e)))))
  (call-hook-updated))

(let [latch (ref (CountDownLatch. 1))
      q (LinkedBlockingQueue.)
      pool (proxy [ThreadPoolExecutor] [1 NTHREADS KEEP-ALIVE TimeUnit/SECONDS q]
             (afterExecute
               [r e]
               (trace (format "added pgm (%d / %d)" (.getActiveCount this) (.size q)))))]
  (defn start-update-api [] (.countDown @latch))
  (defn adding-queue-size [] (.size q))
  (defn add [^Pgm pgm] (.execute pool #(add1 pgm)))
  (defn clean-old [] (.execute pool #(clean-old1))))

