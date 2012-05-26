;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコニコ生放送の番組情報とその操作"}
  nico.pgm
  (:use [clojure.java.io :only [file delete-file]]
        [clojure.set :only [union]]
        [clojure.string :only [lower-case]]
	[clojure.tools.logging])
  (:require [hook-utils :as hu]
            [log-utils :as l]
            [str-utils :as s]
	    [time-utils :as tu]
            [clojure.java.jdbc :as jdbc])
  (:import [clojure.lang Keyword]
           [java.io File]
           [java.sql Connection DriverManager SQLException Timestamp]
           [java.util.concurrent Callable Executors]
           [com.mchange.v2.c3p0 ComboPooledDataSource]))

(def ^{:private true} SCALE 1.1) ;; 最大保持数
(def ^{:private true} INTERVAL-CLEAN 120) ;; 古い番組情報を削除する間隔
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

(defn- create-comms []
  (jdbc/create-table
   :comms
   [:id "varchar(10)" "PRIMARY KEY"]
   [:comm_name "varchar(64)"]
   ;;     [:thumbnail "blob(100K)"]
   [:thumbnail "varchar(64)"]
   [:fetched_at :timestamp]
   [:updated_at :timestamp])
  (jdbc/do-commands "CREATE INDEX idx_comms_id ON comms(id)"
                    "CREATE INDEX idx_comms_name ON comms(comm_name)"
                    "CREATE INDEX idx_comms_fetched_at ON comms(fetched_at)"
                    "CREATE INDEX idx_comms_updated_at ON comms(updated_at)"))

(defn- create-pgms []
  (jdbc/create-table
   :pgms
   [:id "varchar(10)" "PRIMARY KEY"]
   [:title "varchar(64)"]
   [:pubdate :timestamp]
   [:description "varchar(256)"]
   [:category "varchar(24)"]
   [:link "varchar(42)"]
   [:owner_name "varchar(64)"]
   [:member_only :smallint] ;; 1: true 0: false
   [:type :smallint] ;; 0: community 1: channel 2: official
   [:comm_id "varchar(10)"]
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
                    "CREATE INDEX idx_pgms_fetched_at ON pgms(fetched_at)"
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
      (jdbc/insert-values :pmeta
                          [:total :last_cleaned_at]
                          [0 (tu/sql-now)])
      (create-pgms)
      (create-comms))))

(defn- pool [path]
  (let [cpds (doto (ComboPooledDataSource.)
               (.setDriverClass DB-CLASSNAME)
               (.setJdbcUrl (str "jdbc:derby:" path))
               (.setMaxIdleTimeExcessConnections (* 2 60))
               (.setMaxIdleTime 60))]
    {:datasource cpds}))

(defn- delete-all-files [path]
  (let [f (file path)]
    (when (.exists f)
      (if (.isDirectory f)
        (l/with-debug (format "deleting all children of "  path)
          (doseq [c (.listFiles f)] (delete-all-files c))
          (.delete f))
        (l/with-debug (format "deleting file: %s" path)
          (.delete f))))))

(defn- shutdown-db [path]
  (try
    (DriverManager/getConnection (format "jdbc:derby:%s;shutdown=true" path))
    (catch Exception e
      (debug (format "shutdown derby: %s" (.getMessage e)))
      (delete-all-files path))))

(let [db-path (.getCanonicalPath (File/createTempFile "nico-" nil))
      pooled-db (atom nil)]
  (defn init-db []
    (delete-all-files db-path)
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
     (when-not (tu/within? @called-at-hook-updated (tu/now) 3)
       (run-hooks :updated)
       (ref-set called-at-hook-updated (tu/now))))))

;; 以下はリファクタリング用に同じ物をとりあえず実装する。
;; 動くようになったら徐々に不要なメソッドをなくしてメモリ効率を良くしていく
(defn- get-row-comm [^String comm_id]
  (jdbc/with-query-results rs ["SELECT * FROM comms WHERE id=?" comm_id]
    (first rs)))

(defn- gen-pgm [row-pgm]
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
          (let [p (gen-pgm r)]
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
    (if-let [row-pgm (get-pgm-aux (name id))] (gen-pgm row-pgm) nil)))

(defn update-alerted [^Keyword id]
  (jdbc/with-connection (db)
    (jdbc/transaction
     (jdbc/update-values :pgms ["id=?" (name id)] {:alerted true}))))

(defn- clean-old-aux []
  (let [total (get-pmeta :total)
        cnt (count-pgms-aux)
        threshold (int (* SCALE total))] ;; 総番組数のscale倍までは許容
    (when (and (< 0 total) (< threshold cnt))
      ;; 更新時刻の古い順に多すぎる番組を削除する。
      (debug (format "cleaning old: %d -> %d" cnt threshold))
      (jdbc/transaction
       (jdbc/delete-rows
        :pgms
        ["id IN (SELECT id FROM (SELECT ROW_NUMBER() OVER() AS r, pgms.id FROM pgms ORDER BY updated_at) AS tmp WHERE r <= ?)" (- cnt threshold)])
       (jdbc/update-values :pmeta ["id=?" 1] {:last_cleaned_at (tu/sql-now)}))
      (debug (format "cleaned old: %d -> %d" cnt (count-pgms-aux))))))

(defn get-total []
  (jdbc/with-connection (db)
    (get-pmeta :total)))

(defn set-total [total]
  (jdbc/with-connection (db)
    (let [old-total (get-pmeta :total)]
      (jdbc/transaction
       (jdbc/update-values :pmeta ["id=?" 1] {:total total}))
      (debug (format "set-total: %d -> %d" old-total total))
      (when-not (tu/within? (tu/timestamp-to-date (get-pmeta :last_cleaned_at)) (tu/now) INTERVAL-CLEAN)
        (clean-old-aux)))
    (call-hook-updated)))

(defn- rem-pgm-by-comm-id [^String comm_id]
  (jdbc/delete-rows :pgms ["comm_id=?" comm_id]))

(defn- merge-pgm [row-pgm ^Pgm pgm]
  (letfn [(longer [^String x ^String y]
            (cond (and x y) (if (> (.length x) (.length y)) x y)
                  (nil? x) y
                  (nil? y) x
                  :else nil))
          (longer-for [k x ^Pgm y]
            (longer (get x k) (s/trim-to (get y k) (condp = k :title 64 :description 256))))
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

(defn- add-aux [^Pgm pgm]
  (let [pid (name (:id pgm))
        comm_id (if-let [cid (:comm_id pgm)] (name cid) nil)]
    (if-let [row-pgm (get-pgm-aux pid)]
      (jdbc/transaction
       (let [diff-pgm (merge-pgm row-pgm pgm)]
         (debug (format "update pgm (%s) with %s" pid (pr-str diff-pgm)))
         (jdbc/update-values :pgms ["id=?" pid] diff-pgm)))
      (jdbc/transaction
       (debug (format "add pgm: %s" pid))
       (rem-pgm-by-comm-id comm_id) ; 同じコミュニティの番組があったら削除する
       (jdbc/insert-values :pgms
                           [:id :title :pubdate :description :category :link :owner_name
                            :member_only :type :comm_id :alerted :fetched_at :updated_at]
                           [pid (s/trim-to (:title pgm) 64)
                            (tu/date-to-timestamp (:pubdate pgm))
                            (s/trim-to (:desc pgm) 256) (:category pgm) (:link pgm)
                            (s/trim-to (:owner_name pgm) 64)
                            (if (:member_only pgm) 1 0)
                            (condp = (:type pgm) :community 0 :channel 1 :official 2)
                            comm_id
                            (if (:alerted pgm) 1 0)
                            (tu/date-to-timestamp (:fetched_at pgm))
                            (tu/date-to-timestamp (:updated_at pgm))])
       (when comm_id
         (if-let [row-comm (get-row-comm comm_id)]
           (when (tu/within? (tu/timestamp-to-date (:updated_at row-comm)) (tu/now) 3600)
             (jdbc/update-values :comms ["id=?" comm_id]
                                 {:comm_name (s/trim-to (:comm_name pgm) 64)
                                  :thumbnail (:thumbnail pgm)
                                  :updated_at (tu/sql-now)}))
           (jdbc/insert-values :comms
                               [:id :comm_name :thumbnail :fetched_at :updated_at]
                               [comm_id (s/trim-to (:comm_name pgm) 64)
                                (:thumbnail pgm) (tu/sql-now) (tu/sql-now)])))))))

(defn- add1 [^Pgm pgm]
  (jdbc/with-connection (db)
    (add-aux pgm)
    (call-hook-updated)))

(let [pool (Executors/newSingleThreadExecutor)] ; 番組追加リクエストを処理するワーカースレッドプール
  (defn add [^Pgm pgm] (.execute pool #(add1 pgm))))

