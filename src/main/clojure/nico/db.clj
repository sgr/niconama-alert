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

(defn- add!*
  "番組情報をDBに登録する。[追加レコード数 既存レコード削除数]を返す。
   
   同じ番組情報が既に登録されている場合は更新する
    -> 時刻系(:open_time :start_time :updated_at)とその他(異なる場合)
   同じコミュニティIDの古い番組が既に登録されている場合は削除した上で新しい番組を登録する"
  [db pgms]
  (letfn [(query-pgm-id [npgms] (str "SELECT * FROM pgms WHERE id IN (" (s/join "," (repeat npgms "?")) ")"))
          (query-comm-id [npgms] (str "SELECT * FROM pgms WHERE comm_id IN (" (s/join "," (repeat npgms "?")) ")"))
          (diff-row [new old]
            (let [[only-new only-old both] (cd/diff new old)]
              (reduce (fn [m [k v]]
                        (let [ov (get old k)]
                          (assoc m k (condp instance? v
                                       String (if (> (count v) (count ov)) v ov)
                                       Long (max v ov)
                                       v))))
                      {} only-new)))]
    ;; epgms 同一IDを持つキャッシュ済みの情報 -> カラムごとに比較して必要に応じ更新
    ;; pgms0 epgmsと比較する
    ;; pgms1 テンポラリ
    ;; pgms2 公式の新規番組 -> 追加してよい
    ;; pgms3 テンポラリ
    ;; opgms 同一コミュニティの古いキャッシュ済み情報 -> 削除
    ;; pgms4 pgmsのうち同一コミュニティのキャッシュ済み情報より新しいと確認できたもの -> 追加してよい
    (let [epgms (jdbc/query db (vec (concat [(query-pgm-id (count pgms))] (map :id pgms))))
          epids (->> epgms (map :id) set)
          [pgms0 pgms1] (let [m (group-by #(contains? epids (:id %)) pgms)]
                          [(get m true) (get m false)])
          [pgms2 pgms3] (let [m (group-by #(s/blank? (:comm_id %)) pgms1)]
                          [(get m true) (get m false)])
          cmap (reduce #(assoc %1 (:comm_id %2) %2) {} pgms3)
          comm-ids (keys cmap)
          [opgms pgms4] (loop [cpgms (jdbc/query db (vec (concat [(query-comm-id (count comm-ids))] comm-ids)))
                               opgms []
                               cmap cmap]
                          (if-let [cpgm (first cpgms)]
                            (let [comm_id (:comm_id cpgm)
                                  npgm (get cmap comm_id)]
                              (if (> (:start_time npgm) (:start_time cpgm))
                                (recur (rest cpgms) (conj opgms cpgm) cmap)
                                (recur (rest cpgms) opgms (dissoc cmap comm_id))))
                            [opgms (vals cmap)]))]
      (log/infof "PGMS2 (%d), PGMS4 (%d), EPGMS (%d), OPGMS (%d)"
                 (count pgms2) (count pgms4) (count epgms) (count opgms))
      (jdbc/with-db-transaction [db db]
        (jdbc/delete! db :pgms (-> [(str "id IN (" (s/join "," (repeat (count opgms) "?")) ")")
                                    opgms] flatten vec))
        (doseq [pgm (concat pgms2 pgms4)] (jdbc/insert! db :pgms pgm))
        (when (pos? (count epgms)) ;; 要比較更新
          (let [pmap (reduce #(assoc %1 (:id %2) %2) {} pgms0)]
            (doseq [epgm epgms]
              (let [pgm (get pmap (:id epgm))
                    upd-kvs (diff-row pgm epgm)]
                (when-not (empty? upd-kvs) (jdbc/update! db :pgms upd-kvs ["id=?" (:id epgm)])))))))
      [(+ (count pgms2) (count pgms4)) (count opgms)])))

(defn- add! [db pgms]
  (let [upgms (loop [pgms pgms upgms [] ids #{}]
                (if-let [pgm (first pgms)]
                  (if (contains? ids (:id pgm))
                    (recur (rest pgms) upgms ids)
                    (recur (rest pgms) (conj upgms pgm) (conj ids (:id pgm))))
                  upgms))]
    (try
      (add!* db upgms)
      (catch Exception e
        (log/errorf e "failed add!: %s" (pr-str (map :id pgms)))
        [0 0]))))

(defn ^String now-str []
  (let [^FastDateFormat f (FastDateFormat/getInstance "HH:mm:ss")]
    (.format f (System/currentTimeMillis))))

(def QS-STD
  {:n-pgms "SELECT COUNT(*) AS cnt FROM pgms"
   :clean (str "DELETE FROM pgms WHERE id IN "
               "(SELECT id FROM pgms WHERE open_time < ? AND updated_at < ? ORDER BY updated_at LIMIT ?)")
   :freelist_count "PRAGMA freelist_count"
   :page_count     "PRAGMA page_count"
   :vacuum         "VACUUM"
   :shrink_memory  "PRAGMA shrink_memory"})

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
            (jdbc/query db [q] :row-fn gen-pgm))
          (search-pgms-by-queries [db]
            (reduce (fn [m [id q]] (assoc m id (search-pgms db q))) {} @(:qs db)))
          (count-pgms [db query target]
            (let [q (format "SELECT COUNT(*) AS cnt, %s AS %s FROM pgms WHERE %s"
                            (s/join " || " (map name target)) CCOL (where-clause query))]
              (jdbc/query db [q] :result-set-fn first :row-fn :cnt)))
          (n-pgms [db]
            (jdbc/query db [(:n-pgms QS-STD)] :result-set-fn first :row-fn :cnt))
          (timel-before [min]
            (- (System/currentTimeMillis) (* 60000 min)))
          (clean! [db target-val]
            (jdbc/execute! db [(:clean QS-STD) (timel-before 30) (timel-before 5) target-val]))
          (pragma-query [db k]
            (when-let [q (get QS-STD k)]
              (jdbc/query db [q] :result-set-fn first :row-fn k)))
          (now [] (System/currentTimeMillis))]
    (let [RATIO 1.05
          CLEAN-INTERVAL 30000
          SEARCH-INTERVAL 5000
          SEARCH-LIMIT 50
          FREELIST-THRESHOLD 10
          cc (ca/chan)]
      (Class/forName (:classname DB-SPEC))
      (ca/go-loop [db {:connection (DriverManager/getConnection
                                    (format "jdbc:%s:%s" (:subprotocol DB-SPEC) (:subname DB-SPEC)))
                       :qs (atom {})} ; key: id, value: query string
                   total 0 ; ニコ生から得た総番組数。cleanタイミングを決めるのに用いる。
                   npgms 0 ; DBに格納されている総番組数。cleanやvacuumタイミングを決めるのに用いる。
                   last-cleaned (now) ; 最終削除時刻。削除頻度を上げ過ぎないために用いる。
                   last-searched (now) ; 最終検索時刻。オンデマンド検索は含まない。検索頻度を上げ過ぎないために用いる。
                   acc-rm 0] ; 累積削除数。vacuumタイミングを決めるのに用いる。

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
           (jdbc/execute! db [(:vacuum QS-STD)] :transaction? false)
           (jdbc/execute! db [(:shrink_memory QS-STD)] :transaction? false)
           (log/infof "freelist / page: [%d, %d] -> [%d, %d]" fc pc
                      (pragma-query db :freelist_count) (pragma-query db :page_count))
           (recur db total npgms last-cleaned last-searched 0))

         :else
         (if-let [c (ca/<! cc)]
           (condp = (:cmd c)
             :create-db (let [now (now)]
                          (create-db db)
                          (recur db 0 0 now now 0))
             :set-query-kwd (let [{:keys [id query target]} c
                                  q (sql-kwd query target)]
                              (swap! (:qs db) assoc id q)
                              (ca/>! oc-status {:status :searched :results (search-pgms-by-queries db)})
                              (recur db total npgms last-cleaned last-searched acc-rm))
             :set-query-user (let [{:keys [id comms]} c
                                   q (sql-comms comms)]
                               (swap! (:qs db) assoc id q)
                               (ca/>! oc-status {:status :searched :results (search-pgms-by-queries db)})
                               (recur db total npgms last-cleaned last-searched acc-rm))
             :rem-query (let [id (:id c)]
                          (swap! (:qs db) dissoc id)
                          (recur db total npgms last-cleaned last-searched acc-rm))
             :add-pgm  (let [[ins rm] (add! db [(:pgm c)])
                             npgms (n-pgms db)]
                         (ca/>! oc-status {:status :db-stat :npgms npgms :last-updated (now-str) :total total})
                         (when (some pos? [ins rm])
                           (ca/>! oc-status {:status :searched :results (search-pgms-by-queries db)}))
                         (recur db total npgms last-cleaned (if (some pos? [ins rm]) (now) last-searched) (+ acc-rm rm)))
             :add-pgms (let [{:keys [pgms force-search]} c
                             [ins rm] (add! db pgms)
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
               (.close (:connection db))
               (catch Exception e
                 (log/errorf e "failed closing connection")))))))
      (ca/>!! cc {:cmd :create-db})
      cc)))
