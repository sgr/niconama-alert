;; -*- coding: utf-8-unix -*-
(ns nico.db
  (:require [clojure.core.async :as ca]
            [clojure.data :as cd]
            [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [clojure.string :as s]
            [clojure.tools.logging :as log]
            [input-parser.cond-parser :as cp]
            [nico.image :as img]
            [nico.pgm :as pgm])
  (:import [java.sql DriverManager PreparedStatement Statement]
           [java.util LinkedHashMap]
           [org.apache.commons.lang3.time FastDateFormat]))

;; DDL ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^{:private true} DB-SPEC {:classname "org.sqlite.JDBC"
                               :subprotocol "sqlite"
                               :subname ":memory:"})

(def ^{:private true} CCOL "ccol") ;; concatenated column

(defn- create-db [db]
  (letfn [(varchar [len] (format "VARCHAR(%d)" len))]
    (jdbc/db-do-commands
     db true
     (jdbc/create-table-ddl
      :pgms
      [:id (varchar 12) "PRIMARY KEY"]
      [:title (varchar 64)]
      [:open_time :timestamp]
      [:start_time :timestamp]
      [:description (varchar 256)]
      [:category (varchar 24)]
      [:link (varchar 42)]
      [:thumbnail (varchar 64)]
      [:owner_name (varchar 64)]
      [:member_only :smallint] ; true: 1, false: 0
      [:type :smallint] ; community: 0, channel: 1, official: 2
      [:comm_id (varchar 10)]
      [:comm_name (varchar 64)]
      [:fetched_at :timestamp]
      [:updated_at :timestamp]
      :table-spec "WITHOUT ROWID"))
    ;; No indices are needed on in-memory database.
    ;; "CREATE INDEX idx_pgms_id ON pgms(id)"
    ;; "CREATE INDEX idx_pgms_title ON pgms(title)"
    ;; "CREATE INDEX idx_pgms_open_time ON pgms(open_time)"
    ;; "CREATE INDEX idx_pgms_start_time ON pgms(start_time)"
    ;; "CREATE INDEX idx_pgms_description ON pgms(description)"
    ;; "CREATE INDEX idx_pgms_category ON pgms(category)"
    ;; "CREATE INDEX idx_pgms_owner_name ON pgms(owner_name)"
    ;; "CREATE INDEX idx_pgms_comm_id ON pgms(comm_id)"
    ;; "CREATE INDEX idx_pgms_comm_name ON pgms(comm_name)"
    ;; "CREATE INDEX idx_pgms_updated_at ON pgms(updated_at)"
    "PRAGMA journal_mode = OFF"))

;; Query generator ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^{:private true} prefixes #{:not})
(def ^{:private true} infixes #{:and :or})

(defn where-clause [q-str]
  (letfn [(like [word col-str] (format "(%s LIKE '%%%s%%')" col-str word))
          (cname [kwd] (s/upper-case (name kwd)))
          (where-clause-aux [q col-str]
            (cond
             (not (list? q)) (-> (if (keyword? q) (name q) q)
                                 (like col-str))
             (< 1 (count q)) (let [op (first q)]
                               (cond
                                (prefixes op) (if (= 1 (count (rest q)))
                                                (format "(%s %s)" (cname op)
                                                        (where-clause-aux (second q) col-str))
                                                (throw (IllegalArgumentException.
                                                        (format "%s allows one argument only: %s"
                                                                (cname op) (pr-str q)))))
                                (infixes op)  (->> (rest q)
                                                   (map #(where-clause-aux % col-str))
                                                   (s/join (format " %s " (cname op)))
                                                   (format "(%s)"))
                                :else (throw (IllegalArgumentException.
                                              (str "unsupported operator: " (cname op))))))
             :else (throw (IllegalArgumentException.
                           (format "malformed query(%d): %s" (count q) (pr-str q))))))]
    (try
      (when-let [q (cp/parse q-str)]
        (where-clause-aux q CCOL))
      (catch Exception e
        (log/tracef e "failed parsing [%s]" q-str)))))

(defn- where-comms-clause [comms]
  (if (pos? (count comms))
    (->> comms
         (map #(str \' % \'))
         (s/join ",")
         (format "comm_id IN (%s)"))
    "comm_id IN ('NO_COMMUNITY')"))

(defn- sql-comms [comms]
  (str "SELECT * FROM pgms WHERE " (where-comms-clause comms) " ORDER BY open_time DESC"))

(defn- sql-kwd
  ([query target]
     {:pre [(not (s/blank? query)) (pos? (count target)) (every? keyword? target)]}
     (format "SELECT *, %s AS %s FROM pgms WHERE %s ORDER BY open_time DESC"
             (s/join " || " (map name target)) CCOL (where-clause query)))
  ([query target limit]
     {:pre [(not (s/blank? query)) (pos? (count target)) (every? keyword? target) (number? limit) (pos? limit)]}
     (str (sql-kwd query target) " LIMIT " limit)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def QS-STD
  {:pgms-with-id "SELECT * FROM pgms WHERE id=?"
   :pgms-with-comm "SELECT * FROM pgms WHERE comm_id=?"
   :n-pgms "SELECT COUNT(*) AS cnt FROM pgms"
   :clean (str "DELETE FROM pgms WHERE id IN "
               "(SELECT id FROM pgms WHERE open_time < ? AND updated_at < ? ORDER BY updated_at LIMIT ?)")
   :insert (str "INSERT INTO pgms ( "
                (s/join "," (nico.pgm.Pgm/getBasis))
                " ) VALUES ( "
                (s/join "," (-> (nico.pgm.Pgm/getBasis) count (repeat "?")))
                " )")
   :delete-with-id "DELETE FROM pgms WHERE id=?"
   :freelist_count "PRAGMA freelist_count"
   :page_count     "PRAGMA page_count"
   :vacuum         "VACUUM"
   :reindex        "REINDEX"
   :shrink_memory  "PRAGMA shrink_memory"})

(def PGM-KEYS (map keyword (nico.pgm.Pgm/getBasis)))

(defn- pstmt [db q]
  (cond (and (string? q) (not (s/blank? q))) (jdbc/prepare-statement (jdbc/db-connection db) q)
        (vector? q) (let [p (.prepareStatement (jdbc/db-connection db) (first q))]
                      (doseq [b (rest q)] (.addBatch p b))
                      p)
        :else nil))

(defn- cached-pstmt [db q]
  (let [ps-cache (:ps-cache db)]
    (if-let [p (locking ps-cache (.. ps-cache (get q)))]
      (do
        (log/trace "HIT PSTMT CACHE: " q)
        p)
      (let [p (pstmt db q)]
        (log/debug "NEW PSTMT CACHE: " q)
        (locking ps-cache (.. ps-cache (put q p)))
        p))))

(defn- set-params! [pstmt params]
  (dorun (map-indexed (fn [i param] (.setObject pstmt (inc i) param)) params)))

(defn- execute! [db pstmt-params & {:keys [transaction?] :or {transaction? true}}]
  {:pre [(pos? (count pstmt-params))
         (instance? PreparedStatement (first pstmt-params))]}
  (let [pstmt (first pstmt-params)
        params (rest pstmt-params)]
    (when params (set-params! pstmt params))
    (try
      (if transaction?
        (jdbc/with-db-transaction [db db] (.executeUpdate pstmt))
        (.executeUpdate pstmt))
      (catch Exception e
        (log/errorf e "failed execute!"))
      (finally
        (.clearParameters pstmt)))))

(defn- add!*
  "番組情報をDBに登録する。[追加レコード数 既存レコード削除数]を返す。
   
   同じ番組情報が既に登録されている場合は更新する
    -> 時刻系(:open_time :start_time :updated_at)とその他(異なる場合)
   同じコミュニティIDの古い番組が既に登録されている場合は削除した上で新しい番組を登録する"
  [db pgm]
  (letfn [(pgms-with-id [id] (jdbc/query db [(:pgms-with-id @(:ps-std db)) id]))
          (pgms-with-comm [comm-id] (jdbc/query db [(:pgms-with-comm @(:ps-std db)) comm-id]))
          (insert! [db r]
            (execute! db (-> [(:insert @(:ps-std db)) (map #(get r %) PGM-KEYS)] flatten vec)))
          (delete! [db id]
            (execute! db [(:delete-with-id @(:ps-std db)) id]))
          (diff-row [new old]
            (let [[only-new only-old both] (cd/diff new old)]
              (reduce (fn [m [k v]]
                        (assoc m k (condp instance? v
                                     String (max (count v) (count (get old k)))
                                     Long (max v (get old k))
                                     v)))
                      {} only-new)))
          (update-query [diff]
            (str "UPDATE pgms SET "
                 (s/join "," (map (fn [[k v]] (str (name k) "=" (if (nil? v) "NULL" "?"))) diff))
                 " WHERE id=?"))
          (update! [db id diff]
            (let [q (update-query diff)
                  p (cached-pstmt db q)]
              ;;(log/infof "UPDATE[%s] <- %s" id (pr-str (keys diff)))
              (execute! db (-> [p (vals diff) id] flatten vec))))]

    (let [id (:id pgm)
          irs (pgms-with-id id)]
      (condp = (count irs)
        1 (let [diff (diff-row pgm (first irs))]
            (when-not (empty? diff)
              (update! db id diff))
            [0 0])
        0 (if (s/blank? (:comm_id pgm)) ; 公式の番組はcomm_idがない
            (jdbc/with-db-transaction [db db]
              ;;(log/tracef "INSERT[%s]" id)
              (insert! db pgm)
              [1 0])
            (let [crs (pgms-with-comm (:comm_id pgm))]
              (condp = (count crs)
                0 (jdbc/with-db-transaction [db db]
                    ;;(log/tracef "INSERT[%s]" id)
                    (insert! db pgm)
                    [1 0])
                1 (let [cr (first crs)]
                    (if (= id (:id cr))
                      (let [diff (diff-row pgm cr)]
                        (when-not (empty? diff)
                          (update! db id diff))
                        [0 0])
                      (if (> (:open_time pgm) (:open_time cr))
                        (jdbc/with-db-transaction [db db]
                          ;;(log/tracef "DELETE[%s] & INSERT[%s]" (:id cr) id)
                          (delete! db (:id cr))
                          (insert! db pgm)
                          [1 1])
                        [0 0])))
                (do
                  (log/errorf "too many pgms (%d) has same comm_id (%s): %s -> %s"
                              (count crs) (:comm_id pgm) (pr-str pgm) (pr-str crs))
                  [0 0]))))
        (do
          (log/errorf "too many pgms (%d) has same id (%s): %s -> %s"
                      (count irs) (:id pgm) (pr-str pgm) (pr-str irs))
          [0 0])))))

(defn- add! [db pgm]
  (try
    (add!* db pgm)
    (catch Exception e
      (log/errorf e "failed add!: %s" (pr-str pgm))
      [0 0])))

(defn now-str [] (-> (FastDateFormat/getInstance "HH:mm:ss") (.format (System/currentTimeMillis))))

(defn boot
  "番組情報を保持するDBインスタンスを生成し、コントロールチャネルを返す。
   アウトプットチャネルoc-statusには次のステータスが出力される。
   :db-stat
          {:status :db-stat :npgms [DBに格納されている総番組数] :last-updated [最終更新日時文字列] :total [ニコ生から得た総番組数]}
   :searched :searchによる検索結果。
          {:status :searched :results {key: [チャネルID], val: [検索された番組情報のリスト]}}
   :searched-ondemand :search-ondemandによる検索結果。
          {:status :searched-ondemand :results [検索された番組情報のリスト]}

   アウトプットチャネルocには検索結果が出力される。

   コントロールチャネルは次のコマンドを受理する。
   :add-pgm 番組情報を登録する。
          {:cmd :add-pgm :pgm [番組情報]}
   :add-pgms 番組情報をまとめて登録する。
          {:cmd :add-pgms :pgms [番組情報のリスト] :force-search [番組情報検索するか]}
   :set-total 総番組数を設定する。この情報を元にDB内の古い番組情報を削除する。
          {:cmd :set-total :total [ニコ生から得た総番組数]}
   :finish RSS取得が一巡したことを知らせる。このタイミングで更新用検索をかける。
          {:cmd :finish}
   :search 番組情報を検索する。結果はアウトプットチャネルに返す。
          {:cmd :search :queries {:id [チャネルID] :where [検索クエリ]}}
   :search-ondemand 番組情報を検索する。結果はアウトプットチャネルに返す。
          {:cmd :search-ondemand :query [マッチングクエリ] :target [ターゲット]}
   :set-query-kwd キーワードチャネルのsearch用クエリの登録および更新。
          {:cmd :set-query :id [チャネルID] :query [マッチングクエリ] :target [ターゲット]}
   :set-query-user ユーザーチャネルのsearch用クエリの登録および更新。
          {:cmd :set-query :id [チャネルID] :comms [参加コミュニティIDのset]}
   :rem-query search用クエリの削除。
          {:cmd :rem-query :id [チャネルID]}

   また、次のコマンドが内部的に使用される。
   :create-db
          {:cmd :create-db}"
  [oc-status]
  (letfn [(gen-pgm [row]
            (let [thumbnail (do (img/image (:thumbnail row)))]
              (-> row pgm/map->Pgm (assoc :thumbnail thumbnail))))
          (search-pgms [db q]
            (jdbc/query db [(if (instance? PreparedStatement q) q (cached-pstmt db q))] :row-fn gen-pgm))
          (search-pgms-by-queries [db]
            (reduce (fn [m [id q]] (assoc m id (search-pgms db q))) {} @(:ps db)))
          (count-pgms [db query target]
            (let [q (format "SELECT COUNT(*) AS cnt, %s AS %s FROM pgms WHERE %s"
                            (s/join " || " (map name target)) CCOL (where-clause query))]
              (jdbc/query db [(cached-pstmt db q)] :result-set-fn first :row-fn :cnt)))
          (n-pgms [db]
            (jdbc/query db [(:n-pgms @(:ps-std db))] :result-set-fn first :row-fn :cnt))
          (timel-before [min]
            (- (System/currentTimeMillis) (* 60000 min)))
          (delete! [db id]
            (execute! db [(:delete-with-id @(:ps-std db)) id]))
          (clean! [db target-val]
            (execute! db [(:clean @(:ps-std db)) (timel-before 30) (timel-before 5) target-val]))
          (pragma-query [db k]
            (when-let [p (get @(:ps-std db) k)]
              (jdbc/query db [p] :result-set-fn first :row-fn k)))
          (now [] (System/currentTimeMillis))]
    (let [RATIO 1.05
          CLEAN-INTERVAL 30000
          SEARCH-INTERVAL 5000
          SEARCH-LIMIT 50
          FREELIST-THRESHOLD 10
          CACHE-QS-CAPACITY 64
          cc (ca/chan)]
      (Class/forName (:classname DB-SPEC))
      (ca/go-loop [db {:connection (DriverManager/getConnection
                                    (format "jdbc:%s:%s" (:subprotocol DB-SPEC) (:subname DB-SPEC)))
                       :ps-std (atom {}) ; key: 固有の値, value: pstmt
                       :ps (atom {}) ; key: id, value: pstmt
                       :ps-cache (proxy [LinkedHashMap] [(inc CACHE-QS-CAPACITY) 1.1 true] ; key: sql, value: pstmt
                                   (removeEldestEntry [entry]
                                     (if (> (proxy-super size) CACHE-QS-CAPACITY)
                                       (do
                                         (log/info "Remove from cache-qs: " (.. entry getKey))
                                         (.. entry getValue close)
                                         true)
                                       false)))}
                   total 0 ; ニコ生から得た総番組数。cleanタイミングを決めるのに用いる。
                   npgms 0 ; DBに格納されている総番組数。cleanやvacuumタイミングを決めるのに用いる。
                   last-cleaned (now) ; 最終削除時刻。削除頻度を上げ過ぎないために用いる。
                   last-searched (now) ; 最終検索時刻。オンデマンド検索は含まない。検索頻度を上げ過ぎないために用いる。
                   acc-rm 0] ; 累積削除数。vacuumタイミングを決めるのに用いる。
        ;;(System/gc)
        (cond
         (and (> npgms (int (* RATIO total)) 0) ;; 30分経って更新されてない番組情報は削除する
              (< CLEAN-INTERVAL (- (now) last-cleaned)))
         (let [threshold (int (* RATIO total))]
           (clean! db (- npgms threshold))
           (let [new-npgms (n-pgms db)
                 last-updated (now-str)
                 rm (- npgms new-npgms)
                 new-acc-rm (+ acc-rm rm)
                 now (now)]
             (log/infof "clean! [%d -> %d] / %d acc-rm: %d" npgms new-npgms total new-acc-rm)
             (ca/>! oc-status {:status :db-stat :npgms new-npgms :last-updated last-updated :total total})
             (when (pos? rm)
               (ca/>! oc-status {:status :searched :results (search-pgms-by-queries db)}))
             (recur db
                    total
                    new-npgms
                    now
                    (if (pos? rm) now last-searched)
                    new-acc-rm)))
         
         (and (pos? npgms) (>= acc-rm npgms) ;; acc-rm等を見て必要に応じてvacuumをかける
              (< FREELIST-THRESHOLD (pragma-query db :freelist_count)))
         (let [fc (pragma-query db :freelist_count)
               pc (pragma-query db :page_count)]
           (execute! db [(:vacuum @(:ps-std db))] :transaction? false)
           ;;(execute! db [(:reindex @(:ps-std db))] :transaction? false)
           (execute! db [(:shrink_memory @(:ps-std db))] :transaction? false)
           (log/infof "freelist / page: [%d, %d] -> [%d, %d]" fc pc
                      (pragma-query db :freelist_count) (pragma-query db :page_count))
           (recur db total npgms last-cleaned last-searched 0))

         :else
         (if-let [c (ca/<! cc)]
           (condp = (:cmd c)
             :create-db (let [now (now)]
                          (create-db db)
                          (reset! (:ps-std db) (reduce (fn [m [k q]] (assoc m k (pstmt db q))) {} QS-STD))
                          (recur db 0 0 now now 0))
             :set-query-kwd (let [{:keys [id query target]} c
                                  q (sql-kwd query target)]
                              (when-let [p (get @(:ps db) id)] (.close p))
                              (swap! (:ps db) assoc id (pstmt db q))
                              (ca/>! oc-status {:status :searched :results (search-pgms-by-queries db)})
                              (recur db total npgms last-cleaned last-searched acc-rm))
             :set-query-user (let [{:keys [id comms]} c
                                   q (sql-comms comms)]
                               (when-let [p (get @(:ps db) id)] (.close p))
                               (swap! (:ps db) assoc id (pstmt db q))
                               (ca/>! oc-status {:status :searched :results (search-pgms-by-queries db)})
                               (recur db total npgms last-cleaned last-searched acc-rm))
             :rem-query (let [id (:id c)]
                          (swap! (:ps db) dissoc id)
                          (recur db total npgms last-cleaned last-searched acc-rm))
             :add-pgm  (let [[ins rm] (add! db (:pgm c))
                             npgms (n-pgms db)]
                         (ca/>! oc-status {:status :db-stat :npgms npgms :last-updated (now-str) :total total})
                         (when (some pos? [ins rm])
                           (ca/>! oc-status {:status :searched :results (search-pgms-by-queries db)}))
                         (recur db total npgms last-cleaned (if (some pos? [ins rm]) (now) last-searched) (+ acc-rm rm)))
             :add-pgms (let [{:keys [pgms force-search]} c
                             [ins rm] (loop [ains 0 arm 0 pgms pgms]
                                        (if-let [pgm (first pgms)]
                                          (let [[ins rm] (add! db pgm)]
                                            (recur (+ ains ins) (+ arm rm) (rest pgms)))
                                          [ains arm]))
                             npgms (n-pgms db)
                             new-last-searched (let [now (now)]
                                                 (if (and (< SEARCH-INTERVAL (- now last-searched))
                                                          (some pos? [ins rm]))
                                                   now last-searched))]
                         (ca/>! oc-status {:status :db-stat :npgms npgms :last-updated (now-str) :total total})
                         (when (or force-search (not= last-searched new-last-searched))
                           (ca/>! oc-status {:status :searched :results (search-pgms-by-queries db)}))
                         (recur db total npgms last-cleaned new-last-searched (+ acc-rm rm)))
             :set-total (let [new-total (:total c)]
                          (log/infof "SET-TOTAL: %d -> %d" total new-total)
                          (recur db (or new-total total) npgms last-cleaned last-searched acc-rm))
             :finish (do
                       (ca/>! oc-status {:status :searched :results (search-pgms-by-queries db)})
                       (recur db total npgms last-cleaned (now) acc-rm))
             :search-ondemand (let [{:keys [query target]} c
                                    cnt (count-pgms db query target)
                                    q (if (< SEARCH-LIMIT cnt)
                                        (sql-kwd query target SEARCH-LIMIT)
                                        (sql-kwd query target))]
                                (ca/>! oc-status {:status :searched-ondemand :cnt cnt :results (search-pgms db q)})
                                (recur db total npgms last-cleaned last-searched acc-rm)) ;; ここではlast-searched更新しない
             (do
               (log/warnf "caught an unknown command[%s]" (pr-str c))
               (recur db total npgms last-cleaned last-searched acc-rm)))
           (do
             (log/info "closed database control channel")
             (try
               (doseq [pstmt (vals @(:ps-std db))] (.close pstmt))
               (doseq [pstmt (vals @(:ps db))] (.close pstmt))
               (doseq [pstmt (.values (:ps-cache db))] (.close pstmt))
               (catch Exception e
                 (log/warnf e "failed closing pstmts"))
               (finally
                 (try
                   (.close (:connection db))
                   (catch Exception e
                     (log/errorf e "failed closing connection")))))))))
      (ca/>!! cc {:cmd :create-db})
      cc)))
