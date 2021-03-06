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
  (:import [java.sql DriverManager]
           [org.apache.commons.lang3.time FastDateFormat]))

;; DDL ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^{:private true} CONN-URI "jdbc:sqlite::memory:") ;; "jdbc:sqlite:file::memory:?cache=shared"
(def ^{:private true} CCOL "ccol") ;; concatenated column

(defn- create-db! [db]
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
    "CREATE INDEX idx_pgms_id ON pgms(id)"
    "CREATE INDEX idx_pgms_title ON pgms(title)"
    "CREATE INDEX idx_pgms_open_time ON pgms(open_time)"
    "CREATE INDEX idx_pgms_start_time ON pgms(start_time)"
    "CREATE INDEX idx_pgms_description ON pgms(description)"
    "CREATE INDEX idx_pgms_category ON pgms(category)"
    "CREATE INDEX idx_pgms_owner_name ON pgms(owner_name)"
    "CREATE INDEX idx_pgms_comm_id ON pgms(comm_id)"
    "CREATE INDEX idx_pgms_comm_name ON pgms(comm_name)"
    "CREATE INDEX idx_pgms_updated_at ON pgms(updated_at)"
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
        (log/warnf e "failed parsing [%s]" q-str)))))

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
  (letfn [(query-pgm-id [n]
            (str "SELECT * FROM pgms WHERE id IN (" (s/join "," (repeat n "?")) ")"))
          (query-comm-id [n]
            (str "SELECT id, comm_id, start_time FROM pgms WHERE comm_id IN (" (s/join "," (repeat n "?")) ")"))
          (diff-row [new old]
            (let [[only-new only-old both] (cd/diff new old)]
              (reduce (fn [m [k v]]
                        (let [ov (get old k)]
                          (assoc m k (condp instance? v
                                       String (if (> (count v) (count ov)) v ov)
                                       Long (max v ov)
                                       v))))
                      {} only-new)))]
    ;; epgms 同一IDを持つキャッシュ済みの情報 -> pgms0と比較して必要に応じカラム更新
    ;; pgms0 追加しようとしている番組情報 -> epgmsと比較して必要に応じカラム更新
    ;; pgms1 テンポラリ
    ;; pgms2 公式の新規番組 -> 追加してよい
    ;; pgms3 テンポラリ
    ;; opgms 同一コミュニティの番組情報 -> 削除
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
      ;; (log/tracef "PGMS (%d) -> PGMS2 (%d), PGMS4 (%d), EPGMS (%d), OPGMS (%d)"
      ;;             (count pgms) (count pgms2) (count pgms4) (count epgms) (count opgms))
      (jdbc/with-db-transaction [db db]
        (jdbc/delete! db :pgms (-> [(str "id IN (" (s/join "," (-> opgms count (repeat "?"))) ")")
                                    (map :id opgms)] flatten vec))
        (doseq [pgm (concat pgms2 pgms4)] (jdbc/insert! db :pgms pgm))
        (when (pos? (count epgms)) ;; 要比較更新
          (let [pmap (reduce #(assoc %1 (:id %2) %2) {} pgms0)]
            (doseq [epgm epgms]
              (let [pgm (get pmap (:id epgm))
                    upd-kvs (diff-row pgm epgm)]
                (when-not (empty? upd-kvs)
                  (jdbc/update! db :pgms upd-kvs ["id=?" (:id epgm)])))))))
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
        (log/errorf e "failed add! pgms (%s)" (pr-str (map :id upgms)))
        [0 0]))))

(defn- vacuum!
  "Sqliteのin-memory DBに対するVACUUM代替処理。
  in-memory DBはVACUUMクエリが無効なようなので(少なくともMac版はできてなさげ)、
  新しいin-memory DBを作成し今のDBの内容をコピーする、というかなり雑な処理である。
  なお本プログラムのDBは1テーブルしかなく行数も7000を超えない程度しかない小規模なものであり、
  実測で繁忙期でも高々1.3秒程度しかかからないことがわかったため、RSS登録サイクルの終了時に毎回実行する。"
  [db]
  (let [ndb (assoc db :connection (DriverManager/getConnection CONN-URI))]
    (create-db! ndb)
    (jdbc/with-db-transaction [ndb ndb]
      (jdbc/query db "SELECT * FROM pgms" :row-fn #(jdbc/insert! ndb :pgms %)))
    ndb))

(defn boot
  "番組情報を保持するDBインスタンスを生成し、コントロールチャネルを返す。
   アウトプットチャネルoc-uiには次のステータスが出力される。
   :db-stat
          {:status :db-stat :npgms [DBに格納されている総番組数] :last-updated [最終更新日時文字列] :total [ニコ生から得た総番組数]}
   :searched :searchによる検索結果。
          {:status :searched :results {key: [チャネルID], val: [検索された番組情報のリスト]}}
   :searched-ondemand :search-ondemandによる検索結果。
          {:status :searched-ondemand :results [検索された番組情報のリスト]}

   コントロールチャネルは次のコマンドを受理する。
   :add-pgms 番組情報をまとめて登録する。
          {:cmd :add-pgms :pgms [番組情報のリスト] :force-search [番組情報検索するか]}
   :set-total 総番組数を設定する。この情報を元にDB内の古い番組情報を削除する。
          {:cmd :set-total :total [ニコ生から得た総番組数]}
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
  [oc-ui]
  (letfn [(gen-pgm [r]
            (assoc r :thumbnail_image (img/image (:thumbnail r))))
          (search-pgms [db q]
            (do (jdbc/query db [q] :row-fn gen-pgm)))
          (search-pgms-by-queries [db]
            (->> @(:qs db)
                 (reduce (fn [m [id q]] (assoc m id (search-pgms db q))) {})))
          (count-pgms [db query target]
            (let [q (format "SELECT COUNT(*) AS cnt, %s AS %s FROM pgms WHERE %s"
                            (s/join " || " (map name target)) CCOL (where-clause query))]
              (jdbc/query db [q] :result-set-fn first :row-fn :cnt)))
          (n-pgms [db]
            (jdbc/query db ["SELECT COUNT(*) AS cnt FROM pgms"] :result-set-fn first :row-fn :cnt))
          (timel-before [min]
            (- (System/currentTimeMillis) (* 60000 min)))
          (clean! [db target-val]
            (let [q ["id IN (SELECT id FROM pgms WHERE open_time < ? AND updated_at < ? ORDER BY updated_at LIMIT ?)"
                     (timel-before 30) (timel-before 5) target-val]]
              (apply + (jdbc/delete! db :pgms q))))]
    (let [KEEP-RATIO 1.05 ; ニコ生から得られた全数に対してDBの保持数がこの比率を超えたら削除する
          CLEAN-INTERVAL 30000 ; 古い番組情報を削除間隔ミリ秒
          SEARCH-INTERVAL 5000 ; 通常検索の実行間隔ミリ秒
          SEARCH-LIMIT 50 ; オンデマンド検索の検索結果上限
          ^FastDateFormat fmt (FastDateFormat/getInstance "HH:mm:ss")
          cc (ca/chan)]
      (Class/forName "org.sqlite.JDBC")
      (ca/go-loop [db {:connection (DriverManager/getConnection CONN-URI) ; メモリDB保持のため
                       :qs (atom {})} ; UIで設定されたクエリー。key: id, value: query string
                   total 0 ; ニコ生から得た総番組数。
                   npgms 0 ; DBに格納されている総番組数。
                   last-cleaned  0  ; 最終削除時刻。削除頻度を上げ過ぎないために用いる。
                   last-searched 0] ; 最終検索時刻。オンデマンド検索は含まない。検索頻度を上げ過ぎないために用いる。
        (if-let [c (ca/<! cc)]
          (condp = (:cmd c)
            :create-db (let [now (System/currentTimeMillis)]
                         (create-db! db)
                         (recur db 0 0 now now))
            :set-query-kwd (let [{:keys [id query target]} c
                                 q (sql-kwd query target)]
                             (swap! (:qs db) assoc id q)
                             (ca/>! oc-ui {:status :searched :results (search-pgms-by-queries db)})
                             (recur db total npgms last-cleaned last-searched))
            :set-query-user (let [{:keys [id comms]} c
                                  q (sql-comms comms)]
                              (swap! (:qs db) assoc id q)
                              (ca/>! oc-ui {:status :searched :results (search-pgms-by-queries db)})
                              (recur db total npgms last-cleaned last-searched))
            :rem-query (let [id (:id c)]
                         (swap! (:qs db) dissoc id)
                         (recur db total npgms last-cleaned last-searched))
            :add-pgms (let [{:keys [pgms force-search]} c
                            [ins rm] (if (and pgms (-> pgms count pos?)) (add! db pgms) [0 0])
                            now (System/currentTimeMillis)
                            threshold (int (* KEEP-RATIO total))
                            [new-last-cleaned rm2] (if (and (> npgms threshold 0) (< CLEAN-INTERVAL (- now last-cleaned)))
                                                     [now (clean! db (- npgms threshold))]
                                                     [last-cleaned 0])
                            npgms (n-pgms db)
                            ndb (if (and (-> pgms count zero?) (pos? npgms))
                                  (let [a (System/currentTimeMillis)
                                        ndb (vacuum! db)]
                                    ;; RSSサイクルの最後は空のpgmsがくるのでVACUUMする
                                    (log/infof "vacuum (%d msec)" (- (System/currentTimeMillis) a))
                                    (.close (:connection db)) ; 古い方は削除
                                    ndb)
                                  db)
                            new-last-searched (if (or (and (< SEARCH-INTERVAL (- now last-searched)) (some pos? [ins rm]))
                                                      (not= last-cleaned new-last-cleaned)) 
                                                now last-searched)]
                        (ca/>! oc-ui {:status :db-stat :npgms npgms :last-updated (.format fmt (System/currentTimeMillis)) :total total})

                        (when (or force-search (not= last-searched new-last-searched))
                          (ca/>! oc-ui {:status :searched :results (search-pgms-by-queries ndb)}))

                        (recur ndb total npgms new-last-cleaned new-last-searched))
            :set-total (let [new-total (:total c)]
                         (when (pos? new-total) (log/infof "SET-TOTAL: %d -> %d" total new-total))
                         (recur db (if (pos? new-total) new-total total) npgms last-cleaned last-searched))
            :search-ondemand (let [{:keys [query target]} c
                                   cnt (count-pgms db query target)
                                   q (if (< SEARCH-LIMIT cnt)
                                       (sql-kwd query target SEARCH-LIMIT)
                                       (sql-kwd query target))]
                               (ca/>! oc-ui {:status :searched-ondemand :cnt cnt :results (search-pgms db q)})
                               ;; ここではlast-searched更新しない
                               (recur db total npgms last-cleaned last-searched))
            (do
              (log/warnf "caught an unknown command[%s]" (pr-str c))
              (recur db total npgms last-cleaned last-searched)))
          (do
            (log/info "closed database control channel")
            (try
              (.close (:connection db))
              (catch Exception e
                (log/errorf e "failed closing connection"))))))

      (ca/>!! cc {:cmd :create-db})
      cc)))
