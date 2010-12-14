;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコニコ生放送の番組情報とその操作"}
  nico.pgm
  (:use [clojure.contrib.sql]
	[clojure.contrib.sql.internal])
  (:require [time-utils :as tu])
  (:import (clojure.lang RT)
	   (java.io File)
	   (java.text SimpleDateFormat)
	   (java.util Date)
	   (java.sql DriverManager Timestamp ResultSet SQLException)))

(defstruct pgm
  :id		;; 番組ID
  :title	;; タイトル
  :pubdate	;; 開始日時
  :desc		;; 説明
  :category	;; カテゴリ
  :link		;; 番組へのリンク
  :thumbnail	;; コミュニティのサムネイル
  :owner_name	;; 放送者名
  :member_only	;; コミュ限
  :view		;; 来場者数
  :type		;; community or channel
  :num_res	;; コメント数
  :comm_name	;; コミュニティ名
  :comm_id	;; コミュニティID
  :alerted)	;; アラート済み

(gen-class
 :name nico.ResultSetModel
 :extends javax.swing.table.AbstractTableModel
 :prefix "prs-"
 :constructors {[clojure.lang.IPersistentMap] []}
 :state state
 :init init
 :methods [[close [] void]
	   [update [] void]
	   [isNew [int] boolean]
	   [getUrl [int] String]
	   [getProgramId [int] String]
	   [getProgramTitle [int] String]
	   [getPgm [int] clojure.lang.PersistentStructMap]])

(defn- ts [d] (Timestamp. (.getTime d)))
(defn- tsnow [] (ts (tu/now)))

(defn- conv-sql-key [key]
  (condp = key
      :desc :description
      :view :view_count
      key))

(defn- conv-sql-val [key val]
  (condp = key
      :pubdate (ts val)
      :member_only (if (true? val) 1 0)
      :alerted (if (true? val) 1 0)
      val))

(defn- pgm-to-vals [pgm]
  (let [keys (vec (for [k (keys pgm) :when (not (nil? (get pgm k nil)))] k))
	vals (vec (for [k keys] (let [v (get pgm k)] (conv-sql-val k v))))]
    (list (conj (vec (map conv-sql-key keys)) :old :fetched_at)
	  (conj vals 0 (tsnow)))))

(defn- pgm-to-update-vals* [pgm]
  (reduce #(assoc %1 (conv-sql-key %2) (conv-sql-val %2 (get pgm %2))) {}
	  (for [k (keys pgm) :when (not (or (= :id k)
					    (nil? (get pgm k nil))))] k)))

(defn- pgm-to-update-vals [pgm] (assoc (pgm-to-update-vals* pgm) :fetched_at (tsnow)))

(defn- row-to-pgm [row]
  (struct pgm
	  (:id row) (:title row)
	  (Date. (.getTime (:pubdate row)))
	  (:description row) (:category row) (:link row) (:thumbnail row) (:owner_name row)
	  (if (= 1 (:member_only row)) true false)
	  (:view_count row) (:type row) (:num_res row) (:comm_name row) (:comm_id row)
	  (if (= 1 (:alerted row)) true false)))

(defn- delete-file [f]
  (.deleteOnExit f)	; いくつか削除に失敗するファイルがあるため、一応
  (when (.isDirectory f) (doseq [c (.listFiles f)] (delete-file c)))
  (when-not (.delete f)
    (println (format "** failed deleting %s" (.getCanonicalPath f)))))

(let [db-path (let [f (File/createTempFile "nico-" nil)
		    p (.getCanonicalPath f)]
		(.delete f) p)
      db {:classname "org.apache.derby.jdbc.ClientDriver"
	  :subprotocol "derby"
	  :subname "//localhost:1527/nico;user=testuser;password=testpasswd"
	  :create true}
      last-updated (atom (tu/now))]
  (defn- create-tbls* []
    (create-table :pgms
		  [:id "varchar(10)" "PRIMARY KEY"]
		  [:title "varchar(96)"]
		  [:pubdate "timestamp"]
		  [:description "varchar(128)"]
		  [:category "varchar(24)"]
		  [:link "varchar(96)"]
		  [:thumbnail "varchar(96)"]
		  [:owner_name "varchar(48)"]
		  [:member_only "integer"]
		  [:view_count "integer"]
		  [:type "varchar(9)"]
		  [:num_res "integer"]
		  [:comm_name "varchar(96)"]
		  [:comm_id "varchar(10)"]
		  [:alerted "integer"]
		  [:old "integer"]
		  [:fetched_at "timestamp"]))
  (defn- create-idxs* []
    (do-commands
     "create index idx_id on pgms(id)"
     "create index idx_pubdate on pgms(pubdate)"
     "create index idx_old on pgms(old)"))
  (defn init-db []
    (with-connection db
      (transaction
       (create-tbls*)
       (create-idxs*))))
  (defn shutdown-db []
    (with-connection db
      (transaction (drop-table :pgms))))
  (defn shutdown-db-embedded []
    (let [url (format "jdbc:%s:;shutdown=true" (:subprotocol db) (:subname db))]
      (try
	(RT/loadClassForName (:classname db))
	(DriverManager/getConnection url)
	(catch Exception e (println (.getMessage e)))))
    (delete-file (File. db-path)))
  (defn- sql-query [q] (with-query-results res q (doall res)))
  (defn- get-pgm* [id]
    (with-query-results res ["select * from pgms where id=?" id]
      (if res (row-to-pgm (first res)) nil)))
  (defn- get-pgms-by-comm* [comm_id]
    (with-query-results res ["select * from pgms where comm_id=?" comm_id]
      (map #(row-to-pgm %) res)))
  (defn- get-pgm-by-comm* [comm_id] (first (get-pgms-by-comm* comm_id)))
  (defn get-pgm [id] (with-connection db (get-pgm* id)))
  (defn- rem-pgm* [id] (delete-rows :pgms ["id=?" id]))
  (defn rem-pgm [id] (with-connection db (transaction (rem-pgm* id))))
  (defn update-pgm [pgm]
    (with-connection db
      (when (get-pgm* (:id pgm))
	(transaction
	 (update-values :pgms ["id=?" (:id pgm)] (vec (rest (pgm-to-update-vals pgm))))))))
  (defn- add? [pgm]
    (if-not (get-pgm* (:id pgm))
      (if-let [cid (:comm_id pgm)]
	(if-let [apgm (get-pgm-by-comm* cid)]
	  (tu/earlier? (:pubdate apgm) (:pubdate pgm))
	  true)
	false)
      (do
	(update-values :pgms ["id=?" (:id pgm)] {:fetched_at (tsnow)})
	false)))
  (defn- add-pgm* [pgm]
    (when (add? pgm)
      (do
	(when-let [cpgm (if-let [cid (:comm_id pgm)] (get-pgm-by-comm* cid) nil)]
	  (rem-pgm* (:id cpgm)))
	(let [[keys vals] (pgm-to-vals pgm)]
	  (insert-values :pgms keys vals)))))
  (defn add-pgm [pgm] (with-connection db (transaction (add-pgm* pgm))))
  (defn rem-pgms [ids] (with-connection db (transaction (doseq [id ids] (rem-pgm* id)))))
  (defn add-pgms [pgms] (with-connection db (transaction (doseq [pgm pgms] (add-pgm* pgm)))))
  (defn reset-pgms [pgms]
    (with-connection db
      (transaction
       (sql-query ["delete from pgms"])
       (doseq [pgm pgms] (add-pgm* pgm)))))
  (defn- gen-not-in [ids]
    (str "id not in ("
	 (apply str (interpose \, (map (fn [_] "?") ids)))
	 ")"))
  (defn rem-pgms-without
    ([]
       (with-connection db
	 (transaction
	  (delete-rows :pgms ["fetched_at < ?" (ts @last-updated)]))))
    ([date]
       (with-connection db
	 (transaction
	  (delete-rows :pgms ["fetched_at < ? and pubdate > ?" (ts @last-updated) (ts date)])))))
  (defn update-old []
    (with-connection db
      (transaction
       (update-values :pgms ["old=?" 0] {:old 1}))
      (reset! last-updated (tu/now))))
  (defn count-pgms []
    (with-connection db
      (with-query-results res ["select count(*) from pgms"]
	(:1 (first res)))))
  (defn new? [id]
    (with-connection db
      (with-query-results res
	[(str "select count(*) from pgms where id =? and old=?") id 1]
	(if (= 0 (:1 (first res))) true false))))
  (defn model-all-pgms [] (nico.ResultSetModel. db))
  (defn get-pgms-by [filter-fn]
    (with-connection db
      (with-query-results res
	["select * from pgms order by pubdate desc"]
	(reduce (fn [acc r]
		  (let [pgm (row-to-pgm r)]
		    (if (filter-fn pgm) (assoc acc (:id pgm) pgm) acc)))
		{} res)))))


(defn- prs-init [db]
  (let [query "select * from pgms order by pubdate desc"
	conn (get-connection db)
	stmt (.prepareStatement conn
				query ResultSet/TYPE_SCROLL_INSENSITIVE ResultSet/CONCUR_READ_ONLY)
	rs (.executeQuery stmt)]
    [[] (atom {:query query :conn conn :stmt stmt :rs rs})]))

(defn- prs-update [this]
  (let [stmt (:stmt @(.state this))
	oldrs (:rs @(.state this))]
    (swap! (.state this) assoc :rs (.executeQuery stmt))
    (.close oldrs)
    (.fireTableDataChanged this)))

(defn- prs-close [this]
  (let [conn (:conn @(.state this)) stmt (:stmt @(.state this)) rs (:rs @(.state this))]
    (try
      (when rs (.close rs)) (when stmt (.close stmt))
      (finally (try (when conn (.close conn)) (catch Exception e (.printStackTrace e)))))))

(defn- get-row-map [rs row]
  (.absolute rs (inc row))
  (let [md (.getMetaData rs)
	cols (range 1 (inc (.getColumnCount md)))
	keys (map (comp keyword #(.toLowerCase ^String %))
		  (map #(.getColumnLabel md %) cols))
	rst (apply create-struct keys)]
    (apply struct rst (map #(.getObject rs %) cols))))

(defn- prs-getUrl [this row]
  (:link (get-row-map (:rs @(.state this)) row)))

(defn- prs-getProgramId [this row]
  (:id (get-row-map (:rs @(.state this)) row)))

(defn- prs-getProgramTitle [this row]
  (:title (get-row-map (:rs @(.state this)) row)))

(defn- prs-getColumnCount [this] 5)

(defn- prs-getColumnName [this col]
  (nth ["限" "タイトル" "コミュ名" "開始" "放送主"] col nil))

(defn- prs-getRowCount [this]
  (try
    (.last (:rs @(.state this)))
    (.getRow (:rs @(.state this)))
    (catch Exception e (println (.getMessage e)) 0)))

(defn- prs-isNew [this row]
  (if (= 0 (:old (get-row-map (:rs @(.state this)) row))) true false))

(defn- prs-getValueAt [this row col]
  (try 
    (let [m (get-row-map (:rs @(.state this)) row)]
      (condp = col
	  0 (if (= 1 (:member_only m)) true false)
	  1 (:title m)
	  2 (:comm_name m)
	  3 (Date. (.getTime (:pubdate m)))
	  4 (:owner_name m)
	  nil))
    (catch Exception e (println (.getMessage e)) nil)))

(defn- prs-getPgm [this row]
  (row-to-pgm (get-row-map (:rs @(.state this)) row)))

(defn- prs-isCellEditable [this row col] false)
