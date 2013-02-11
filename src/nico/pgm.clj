;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコニコ生放送の番組情報とその操作"}
  nico.pgm
  (:use [clojure.tools.logging])
  (:require [clojure.java.jdbc :as jdbc]
            [log-utils :as l]
            [query-utils :as q]
            [str-utils :as s]
	    [time-utils :as tu]
            [nico.db :as db]
            [nico.thumbnail :as thumbnail])
  (:import [clojure.lang Keyword]
           [java.sql Connection PreparedStatement ResultSet SQLException Timestamp]
           [java.util Calendar Date]))

(def ^{:private true} SCALE 1.05) ;; 番組最大保持数
(def ^{:private true} INTERVAL-CLEAN 180) ;; 古い番組情報を削除する間隔

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

(let [total (atom -1)]
  (defn set-total [ntotal]
    (when-not (= @total ntotal) (reset! total ntotal)))
  (defn get-total [] @total))

(defn- get-row-comm [^String comm_id]
  (db/req-ro
   #(jdbc/with-query-results rs
      [{:concurrency :read-only} "SELECT * FROM comms WHERE id=?" comm_id]
      (first rs))))

(defn- row-to-pgm [row-pgm]
  (let [row-comm (get-row-comm (:comm_id row-pgm))]
    (nico.pgm.Pgm.
     (keyword (:id row-pgm))
     (:title row-pgm)
     (Date. ^long (:pubdate row-pgm))
     (:description row-pgm)
     (:category row-pgm)
     (:link row-pgm)
     (:thumbnail row-comm)
     (:owner_name row-pgm)
     (condp = (:member_only row-pgm) 0 false 1 true)
     (condp = (:type row-pgm) 0 :community 1 :channel 2 :official)
     (:comm_name row-comm)
     (keyword (:comm_id row-pgm))
     (condp = (:alerted row-pgm) 0 false 1 true)
     (Date. ^long (:fetched_at row-pgm))
     (Date. ^long (:updated_at row-pgm)))))

(defn- count-pgms-aux []
  (jdbc/with-query-results rs [{:concurrency :read-only} "SELECT COUNT(*) AS cnt FROM pgms"]
    (let [cnt (:cnt (first rs))]
      (trace (format "count-pgms-aux: %d" cnt))
      cnt)))

(defn count-pgms [] (db/req-ro count-pgms-aux))

(defn- count-comms-aux []
  (jdbc/with-query-results rs [{:concurrency :read-only} "SELECT COUNT(*) AS cnt FROM comms"]
    (let [cnt (:cnt (first rs))]
      (trace (format "count-comms-aux: %d" cnt))
      cnt)))

(defn- get-pgm-aux [^String id]
  (jdbc/with-query-results rs
    [{:concurrency :read-only} "SELECT * FROM pgms WHERE pgms.id=?" id]
    (first rs)))

(defn get-pgm [^Keyword id]
  (db/req-ro
   #(if-let [row-pgm (get-pgm-aux (name id))] (row-to-pgm row-pgm) nil)))

(defn not-alerted
  "Futureを返す。このFutureは、まだアラートを出してない番組なら番組情報を返す。アラート済みならnilを返す。"
  [^Keyword id]
  (letfn [(update-alerted-aux [^String id]
            (trace (format "called update-alerted-aux: %s" id))
            (try
              (jdbc/transaction
               (let [result (jdbc/update-values :pgms ["id=? AND alerted=0" id] {:alerted true})]
                 (if (= 1 (first result)) true false)))
              (catch Exception e
                (debug e (format "failed updating for " id))
                false)))]
    (db/enqueue (fn []
                  (trace (format "called not-alerted: %s" (name id)))
                  (let [nid (name id), result (update-alerted-aux nid)]
                    (debug (format "update-alerted-aux result: %s" (pr-str result)))
                    (if result
                      (if-let [row-pgm (get-pgm-aux nid)]
                        (l/with-trace (format "row-pgm: %s" (pr-str row-pgm))
                          (row-to-pgm row-pgm))
                        (l/with-error (format "failed get-pgm: %s" nid)
                          nil))
                      nil))))))

(defn- rem-pgm-by-id [^String pid]
  (jdbc/delete-rows :pgms ["id=?" pid]))

(defn- get-row-pgm-by-comm-id [^String comm_id]
  (jdbc/with-query-results rs
    [{:concurrency :read-only} "SELECT * FROM pgms WHERE comm_id=?" comm_id]
    (first rs)))

(defn- merge-pgm [row-pgm ^Pgm pgm]
  (letfn [(longer-for [k x ^Pgm y]
            (s/longer (get x k)
                      (s/trim-to (get y k) (condp = k :title db/LEN_PGM_TITLE :description db/LEN_PGM_DESCRIPTION))))
          (later [x y]
            (cond (and x y) (if (tu/later? x y) x y)
                  (nil? x) y
                  (nil? y) x
                  :else nil))
          (later-for [k x ^Pgm y]
            (max (get x k) (.getTime ^Date (.get y k))))
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
   :title (s/trim-to (:title pgm) db/LEN_PGM_TITLE)
   :pubdate (tu/date-to-timestamp (:pubdate pgm))
   :description (s/trim-to (:desc pgm) db/LEN_PGM_DESCRIPTION)
   :category (:category pgm)
   :link (:link pgm)
   :owner_name (s/trim-to (:owner_name pgm) db/LEN_PGM_OWNER_NAME)
   :member_only (:member_only pgm)
   :type (condp = (:type pgm) :community 0 :channel 1 :official 2)
   :comm_id (if-let [cid (:comm_id pgm)] (name cid) nil)
   :alerted (:alerted pgm)
   :fetched_at (tu/date-to-timestamp (:fetched_at pgm))
   :updated_at (tu/date-to-timestamp (:updated_at pgm))})

(let [last-cleaned (atom (tu/now))]
  (letfn [(clean-old2 [num]
            ;; sqliteはtimestamp値をミリ秒longで保持するのでこの値でそのまま比較できるはず。
            (let [start (.getTimeInMillis (doto (Calendar/getInstance) (.add Calendar/MINUTE -30)))]
              (jdbc/transaction
               (jdbc/delete-rows :comms ["id IN (SELECT comm_id FROM pgms WHERE pubdate < ? ORDER BY updated_at LIMIT ?)" start num])
               (jdbc/delete-rows :pgms  ["id IN (SELECT id FROM pgms WHERE pubdate < ? ORDER BY updated_at LIMIT ?)" start num]))
              (reset! last-cleaned (tu/now))))
          (clean-old1 []
            (try
              (when-not (tu/within? @last-cleaned (tu/now) INTERVAL-CLEAN)
                (let [total (get-total)
                      cnt-pgms (count-pgms-aux)
                      cnt-comms (count-comms-aux)
                      threshold (int (* SCALE total))] ;; 総番組数のscale倍までは許容
                  (when (and (< 0 total) (< threshold cnt-pgms))
                    ;; 更新時刻の古い順に多すぎる番組を削除する。
                    (debug (format "cleaning old: %d -> %d, %d" cnt-pgms threshold cnt-comms))
                    (clean-old2 (- cnt-pgms threshold))
                    (debug (format "cleaned old: %d -> %d, %d" cnt-pgms (count-pgms-aux) (count-comms-aux))))))
              (catch Exception e
                (error e (format "failed cleaning old programs: %s" (.getMessage e))))))]
    (defn clean-old [] (db/enqueue clean-old1))))

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
                                    {:comm_name (s/trim-to (:comm_name pgm) db/LEN_COMM_NAME)
                                     :thumbnail (:thumbnail pgm)
                                     :updated_at now})
                (when comm_id
                  (jdbc/insert-record :comms
                                      {:id comm_id
                                       :comm_name (s/trim-to (:comm_name pgm) db/LEN_COMM_NAME)
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
                 (trace (format "update pgm %s (%s) with %s" pid (:title pgm) (pr-str diff-pgm)))
                 (jdbc/update-values :pgms ["id=?" pid] diff-pgm)))
              (if row-comm-pgm ; 同じコミュニティの番組があったらどちらが新しいかを確認する
                (when (> (.getTime ^Date (:pubdate pgm)) (:pubdate row-comm-pgm))
                  (jdbc/transaction ; 自分のほうが新しければ古いのを削除してから追加する
                   (let [old-pid (name (:id row-comm-pgm))
                         old-title (:title row-comm-pgm)]
                     (trace (format "replace pgm from %s (%s) to %s (%s)" pid (:title pgm) old-pid old-title)))
                   (rem-pgm-by-id (:id row-comm-pgm))
                   (add3 pgm)))
                (jdbc/transaction
                 (trace (format "add pgm %s (%s)" pid (:title pgm)))
                 (add3 pgm))))))
        ;; add2はadd1とadd1-pgms双方から呼ばれる。違いはadd1が一つずつ追加するのに対し、
        ;; add1-pgmsは複数を一度で追加する。add1から呼ばれた場合はadd2の中のトランザクションが有効となる。
        ;; add1-pgmsから呼ばれた場合は外側(add1-pgms内)でくくられたトランザクションが有効となる。
        ;; jdbc/transactionはネストされると外側のトランザクションのみが有効となることを利用している。
        (add1 [^Pgm pgm]
          (try
            (add2 pgm)
            (catch Exception e
              (error e (format "failed adding program (%s) %s" (name (:id pgm)) (:title pgm))))))
        (add1-pgms [pgms]
          (try
            (jdbc/transaction
             (doseq [pgm pgms] (add2 pgm)))
            (catch Exception e
              (error e (format "failed adding programs: [%s]" (pr-str pgms))))))]
  (defn add [^Pgm pgm] (db/enqueue #(add1 pgm)))
  (defn add-pgms [pgms] (db/enqueue #(add1-pgms pgms))))

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

(let [pstmts (atom {})
      search-pgms-by-pstmt-aux
      (fn [^PreparedStatement pstmt]
        (if-let [conn (.getConnection pstmt)]
          (let [^ResultSet rs (.executeQuery pstmt)]
            (try
              (rs-to-pgms (jdbc/resultset-seq rs))
              (catch Exception e (error e (format "failed search-pgms-by-pstmt: %s" pstmt)))
              (finally (.close rs))))
          (do
            (error (format "invalid nil connection: %s" (pr-str pstmt)))
            {})))]
  (defn search-pgms-by-pstmt [sql]
    (if @pstmts
      (if-let [pstmt (get @pstmts sql)]
        (search-pgms-by-pstmt-aux pstmt)
        (let [^PreparedStatement new-pstmt (db/ro-pstmt sql)]
          (swap! pstmts assoc sql new-pstmt)
          (search-pgms-by-pstmt-aux new-pstmt)))
      (do
        (debug (format "couldn't search pgms because pstmts have been shutdown-ed: %s" sql))
        {})))
  (defn remove-pstmt [sql]
    (swap! pstmts dissoc sql))
  (db/add-db-hook :shutdown
                  #(let [ps @pstmts]
                     (reset! pstmts nil)
                     (doseq [^PreparedStatement pstmt (vals ps)] (.close pstmt)))))

(defn search-pgms-by-sql [sql]
  (db/req-ro
   #(jdbc/with-query-results rs [{:concurrency :read-only} sql]
      (rs-to-pgms rs))))

(defn search-pgms-by-comm-id [comm_ids]
  (search-pgms-by-sql (get-sql-comm-id comm_ids)))

(defn search-pgms-by-keywords [query targets]
  (search-pgms-by-sql (get-sql-kwds query targets)))
