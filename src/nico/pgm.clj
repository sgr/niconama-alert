;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコニコ生放送の番組情報とその操作"}
  nico.pgm
  (:use [clojure.set :only [union]]
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

(def ^{:private true} SCALE 1.05) ;; 最大保持数
(def ^{:private true} INTERVAL-CLEAN 60) ;; 古い番組情報を削除する間隔

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

(defn- init-db-aux [path]
  (let [db {:classname DB-CLASSNAME
            :subprotocol "derby"
            :subname path
            :create true}]
    (jdbc/with-connection db
      (create-pgms)
      (create-comms))))

(defn- pool [path]
  (let [cpds (doto (ComboPooledDataSource.)
               (.setDriverClass DB-CLASSNAME)
               (.setJdbcUrl (str "jdbc:derby:" path))
               (.setMaxIdleTimeExcessConnections (* 30 60))
               (.setMaxIdleTime (* 3 60 60)))] 
    {:datasource cpds}))

(defn- drop-tbls []
  (doseq [tbl [:pgms :comms]] (jdbc/drop-table tbl)))

(let [called-at-hook-updated (ref (tu/now))] ;; フックを呼び出した最終時刻
  (hu/defhook :updated)
  (defn- call-hook-updated []
    (dosync
     (when-not (tu/within? @called-at-hook-updated (tu/now) 3)
       (run-hooks :updated)
       (ref-set called-at-hook-updated (tu/now))))))

(let [total (atom 0)] ;; 総番組数
  (defn set-total [t]
    (let [old @total]
      (reset! total t)
      (debug (format "set-total: %d -> %d" old t)))
    (call-hook-updated))
  (defn get-total [] @total))

(let [db-path (let [f (File/createTempFile "nico-" nil)
                    p (.getCanonicalPath f)]
                (.delete f) p)
      initialized (atom false)
      pooled-db (delay (pool db-path))]
  (defn init-db [] (init-db-aux db-path))
  (defn- db [] @pooled-db)

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
  (defn count-pgms []
    (jdbc/with-connection (db)
      (jdbc/with-query-results rs ["SELECT COUNT(*) AS cnt FROM pgms"]
        (:cnt (first rs)))))
  (defn- get-pgm-aux [^String id]
    (jdbc/with-query-results rs ["SELECT * FROM pgms WHERE pgms.id=?" id]
      (if-let [row-pgm (first rs)]
        (gen-pgm row-pgm)
        nil)))
  (defn get-pgm [^Keyword id]
    (jdbc/with-connection (db)
      (get-pgm-aux (name id))))
  (defn update-alerted [^Keyword id]
    (jdbc/with-connection (db)
      (jdbc/transaction
       (jdbc/update-values :pgms ["id=?" (name id)] {:alerted true}))))
  (defn- rem-pgm-by-comm-id [^String comm_id]
    (jdbc/delete-rows :pgms ["comm_id=?" comm_id]))
  (defn add [^Pgm pgm]
    (jdbc/with-connection (db)
      (let [pid (name (:id pgm))
            comm_id (if-let [cid (:comm_id pgm)] (name cid) nil)]
        (when-not (get-pgm-aux pid) ; 既にあるレコードは追加しない
          (call-hook-updated)
          (jdbc/transaction
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
                                    (:thumbnail pgm) (tu/sql-now) (tu/sql-now)])))))))))

(comment
(let [total (atom 0) ;; 総番組数
      id-pgms (ref {}) ;; 番組IDをキー、番組を値とするマップ
      idx-comm (ref {}) ;; コミュニティIDをキー、番組IDを値とするマップ
      idx-pubdate (ref (sorted-set-by ;; 開始時刻でソートされた番組からなる集合
			#(let [d (.compareTo (:pubdate %2) (:pubdate %1))]
			   (if (= 0 d) (.compareTo (name (:id %2)) (name (:id %1))) d))))
      idx-updated-at (ref (sorted-set-by ;; 取得時刻でソートされた番組IDからなる集合
			   #(let [d (.compareTo (:updated_at %2) (:updated_at %1))]
			      (if (= 0 d) (.compareTo (name (:id %2)) (name (:id %1))) d))))
      idx-elapsed (ref (sorted-set-by ;; 確認済経過時間でソートされた番組IDからなる集合
			#(letfn [(elapsed
				  [pgm] (- (.getTime (:updated_at pgm)) (.getTime (:pubdate pgm))))]
			   (let [d (- (elapsed %2) (elapsed %1))]
			     (if (= 0 d) (.compareTo (name (:id %1)) (name (:id %2))) d)))))
      last-cleaned (ref (tu/now)) ;; 番組情報の最終クリーンアップ時刻
      called-at-hook-updated (ref (tu/now)) ;; フックを呼び出した最終時刻
      pool (Executors/newSingleThreadExecutor)] ; 番組追加リクエストを処理するワーカースレッドプール
  (defn pgms [] @id-pgms)
  (defn count-pgms [] (count @id-pgms))
  (hu/defhook :updated)
  (defn- call-hook-updated []
    (dosync
     (when-not (tu/within? @called-at-hook-updated (tu/now) 3)
       (run-hooks :updated)
       (ref-set called-at-hook-updated (tu/now)))))
  (defn set-total [t]
    (let [old @total]
      (reset! total t)
      (debug (format "set-total: %d -> %d" old t)))
    (call-hook-updated))
  (defn get-total [] @total)
  (defn get-pgm [^String id] (get @id-pgms id))

  (defn- disj-pgm-idx [aset id]
    (reduce #(disj %1 %2) aset (filter #(= id (:id %)) aset)))
  (defn- conj-pgm-idx [aset pgm]
    (conj (disj-pgm-idx aset (:id pgm)) pgm))
  (defn- log-pgm [^String mode ^Pgm pgm]
    (format "%s: %s %s \"%s\" \"%s\" %s pubdate: %s updated_at: %s elapsed: %d"
	    mode (name (:id pgm)) (if-let [cid (:comm_id pgm)] (name cid) "NONE")
	    (:title pgm) (if-let [cname (:comm_name pgm)] cname "NONE")
	    (:link pgm)
	    (tu/format-time-long (:pubdate pgm))
	    (tu/format-time-long (:updated_at pgm))
	    (- (.getTime (:updated_at pgm)) (.getTime (:pubdate pgm)))))
  (defn- rem-aux [^clojure.lang.Keyword id]
    (when-let [pgm (get @id-pgms id)]
      (trace (log-pgm "rem" pgm))
      (alter idx-elapsed disj-pgm-idx id)
      (alter idx-updated-at disj-pgm-idx id)
      (alter idx-pubdate disj-pgm-idx id)
      (when-let [cid (:comm_id pgm)] (alter idx-comm dissoc cid))
      (alter id-pgms dissoc id)))
  (defn- diff-pgms [^Pgm from ^Pgm to]
    (letfn [(eq? [k ^Pgm x ^Pgm y]
		 (let [f (get x k) t (get y k)]
		   (if-not (or (nil? f) (nil? t)) (= 0 (.compareTo f t))
			   (if (and (nil? f) (nil? t)) true false))))
	    (to-str [o] (condp = (class o)
			  java.util.Date (tu/format-time-long o)
			  o))
	    (diff-str [k ^Pgm x ^Pgm y]
		      (format "%s: \"%s\" -> \"%s\""
			      (name k) (to-str (get x k)) (to-str (get y k))))]
      (apply str (interpose ", "
			    (map #(when-not (eq? % from to) (diff-str % from to))
				 '(:title :pubdate :desc :comm_name :alerted :updated_at))))))
  (defn update-alerted [id]
    (when-let [pgm (get @id-pgms id)]
      (dosync
       (alter id-pgms assoc id (assoc pgm :alerted true)))))
  (defn- add-aux [^Pgm pgm]
    (if-let [orig (get @id-pgms (:id pgm))]
      (trace (format "update: %s %s \"%s\" {%s}"
		     (name (:id pgm)) (if-let [cid (:comm_id pgm)] (name cid) "NONE")
		     (:title pgm) (diff-pgms orig pgm)))
      (trace (log-pgm "add" pgm)))
    (alter id-pgms assoc (:id pgm) pgm)
    (when-let [cid (:comm_id pgm)] (alter idx-comm assoc cid pgm))
    (alter idx-pubdate conj-pgm-idx pgm)
    (alter idx-updated-at conj-pgm-idx pgm)
    (alter idx-elapsed conj-pgm-idx pgm))
  (defn- merge-aux [^Pgm pgm1 ^Pgm pgm2]
    (if (and pgm1 pgm2)
      (letfn [(longer [^String x ^String y]
		      (cond (and x y) (if (> (.length x) (.length y)) x y)
			    (nil? x) y
			    (nil? y) x
			    :else nil))
	      (longer-for [k ^Pgm x ^Pgm y] (longer (get x k) (get y k)))
	      (later [x y]
		     (cond (and x y) (if (tu/later? x y) x y)
			   (nil? x) y
			   (nil? y) x
			   :else nil))
	      (later-for [k ^Pgm x ^Pgm y] (later (get x k) (get y k)))]
	(assoc pgm2
	  :title (longer-for :title pgm1 pgm2)
	  :pubdate (later-for :pubdate pgm1 pgm2)
	  :desc (longer-for :desc pgm1 pgm2)
	  :comm_name (longer-for :comm_name pgm1 pgm2)
	  :alerted (or (:alerted pgm2) (:alerted pgm1))
	  :updated_at (tu/now)))
      (or pgm1 pgm2)))
  (defn- rem-pgms-without-aux [ids]
    (debug (format "removing old pgms: %d" (- (count-pgms) (count ids))))
    (doseq [id (filter #(not (contains? ids %)) (keys @id-pgms))] (rem-aux id)))
  (defn- check-consistency []
    (let [npgms (count-pgms)
	  ncomm (count @idx-comm)
	  npubdate (count @idx-pubdate)
	  nupdated (count @idx-updated-at)
	  nelapsed (count @idx-elapsed)]
      ;; 公式などコミュニティIDがついていない放送もあるためncommでは比較しない。
      (if (= npgms npubdate nupdated nelapsed)
	(format "all numbers are consistently: %d" npgms)
	(let [msg (format "what's wrong! [npgms: %d, npubdate: %d, nupdated: %d, nelapsed: %d (ncomm: %d)]"
			 npgms npubdate nupdated nelapsed ncomm)] 
	  (error msg)
	  msg))))
  (defn- clean-old-aux []
    (let [c (int (* SCALE @total))] ;; 総番組数のscale倍までは許容
      (when (and (< 0 @total) (< c (count-pgms)))
        ;; 5分以内に存在が確認された番組は多くとも残す
        (let [now (tu/now)
              updated
              (set (map #(:id %) (take-while #(tu/within? (:updated_at %) now 300) @idx-updated-at)))]
          (if (<= c (count updated))
            (l/with-debug (format "rem-pgms-without updated (%d, %d <= %d)"
                                  @total c (count updated))
              (rem-pgms-without-aux updated))
            ;; 30分以内に開始された番組は多くとも残す
            (let [with-pubdate
                  (union updated
                         (set (map #(:id %) (take-while #(tu/within? (:pubdate %) now 1800) @idx-pubdate))))]
              (if (<= c (count with-pubdate))
                (l/with-debug (format "rem-pgms-without pubdate (%d, %d <= %d)"
                                      @total c (count with-pubdate))
                  (rem-pgms-without-aux with-pubdate))
                (let [c2 (- c (count with-pubdate))
                      with-elapsed
                      (union with-pubdate
                             (set (map #(:id %) (take c2 (filter #(not (contains? with-pubdate (:id %)))
                                                                 @idx-elapsed)))))]
                  (debug (format "rem-pgms-without elapsed (t: %d, c: %d, c2: %d, with-elapsed: %d)"
                                 @total, c c2 (count with-elapsed)))
                  (rem-pgms-without-aux with-elapsed)))))
          (trace (format "checked consistency: %s" (check-consistency)))))))
  (defn- get-last-cleaned [] @last-cleaned)
  (defn- add1 [^Pgm pgm]
    (letfn [(add2 [^Pgm pgm]
              (add-aux pgm)
              (when-not (tu/within? @last-cleaned (tu/now) INTERVAL-CLEAN)
                (clean-old-aux)
                (ref-set last-cleaned (tu/now)))
              (call-hook-updated))]
      (dosync
       (let [id (:id pgm) cid (:comm_id pgm)]
	 (if-let [opgm (get @id-pgms id)]
	   (add2 (merge-aux pgm opgm))
	   (if-let [cpgm (get @idx-comm cid)]
	     (when (and (not= id (:id cpgm))
			(tu/later? (:pubdate pgm) (:pubdate cpgm)))
	       (rem-aux (:id cpgm))
               (add2 pgm))
	     (add2 pgm)))))))
  (defn add [^Pgm pgm] (.execute pool #(add1 pgm))))
)