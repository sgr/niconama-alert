;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコニコ生放送の番組情報とその操作"}
  nico.pgm
  (:use [clojure.set :only [union]]
	[clojure.contrib.logging])
  (:require [hook-utils :as hu]
	    [time-utils :as tu])
  (:import [java.util.concurrent Callable Executors]))

(def *scale* 1.15) ;; 最大保持数
(def *interval-clean* 60) ;; 古い番組情報を削除する間隔

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
  (defn- merge-aux [^Pgm pgm]
    (if-let [orig (get @id-pgms (:id pgm))]
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
	(assoc orig
	  :title (longer-for :title pgm orig)
	  :pubdate (later-for :pubdate pgm orig)
	  :desc (longer-for :desc pgm orig)
	  :comm_name (longer-for :comm_name pgm orig)
	  :alerted (or (:alerted orig) (:alerted pgm))
	  :updated_at (tu/now)))
      pgm))
  (defn- rem-pgms-without-aux [ids]
    (debug (format "removing old pgms: %d" (- (count-pgms) (count ids))))
    (doseq [id (filter #(not (contains? ids %)) (keys @id-pgms))] (rem-aux id)))
  (defn- clean-old-aux []
    (when (< 0 @total)
      (let [c (int (* *scale* @total))] ;; 総番組数のscale倍までは許容
	(when (< c (count-pgms))
	  (let [now (tu/now)
		updated (set
			 (map #(:id %)
			      (take-while #(tu/within? (:updated_at %) now 300)
					  @idx-updated-at)))]
	    ;; 5分以内に存在が確認された番組は多くとも残す
	    (if (<= c (count updated))
	      (do
		(debug (format "rem-pgms-without updated (%d, %d <= %d)"
			       @total c (count updated)))
		(rem-pgms-without-aux updated))
	      (let [with-pubdate (union updated
					(set
					 (map #(:id %)
					      (take-while #(tu/within? (:pubdate %) now 1800)
							  @idx-pubdate))))]
		;; 30分以内に開始された番組は多くとも残す
		(if (<= c (count with-pubdate))
		  (do
		    (debug (format "rem-pgms-without pubdate (%d, %d <= %d)"
				   @total c (count with-pubdate)))
		    (rem-pgms-without-aux with-pubdate))
		  (let [c2 (- c (count with-pubdate))
			with-elapsed (union with-pubdate
					    (set
					     (map #(:id %)
						  (take c2 (filter
							    #(not (contains? with-pubdate (:id %)))
							    @idx-elapsed)))))]
		    (debug (format "rem-pgms-without elapsed (t: %d, c: %d, c2: %d, with-elapsed: %d)"
				   @total, c c2 (count with-elapsed)))
		    (rem-pgms-without-aux with-elapsed))))))))))
  (defn- check-consistency []
    (let [npgms (count-pgms)
	  ncomm (count @idx-comm)
	  npubdate (count @idx-pubdate)
	  nupdated (count @idx-updated-at)
	  nelapsed (count @idx-elapsed)]
      ;; 公式などコミュニティIDがついていない放送もあるためncommでは比較しない。
      (if (= npgms npubdate nupdated nelapsed)
	npgms
	(do
	  (error (format "pgms: %d, pubdate: %d, updated: %d, elapsed: %d (comm: %d)"
			 npgms npubdate nupdated nelapsed ncomm))
	  nil))))
  (defn- get-last-cleaned [] @last-cleaned)
  (defn- add1 [^Pgm pgm]
    (letfn [(add-clean [^Pgm pgm]
		       (add-aux pgm)
		       (when-not (tu/within? @last-cleaned (tu/now) *interval-clean*)
			 (do (clean-old-aux)
			     (ref-set last-cleaned (tu/now))))
		       (check-consistency)
		       (call-hook-updated))]
      (dosync
       (let [id (:id pgm) cid (:comm_id pgm)]
	 (if (contains? @id-pgms id)
	   (add-clean (merge-aux pgm))
	   (if-let [opgm (get @idx-comm cid)]
	     (when (and (not= id (:id opgm))
			(tu/later? (:pubdate pgm) (:pubdate opgm)))
	       (do (rem-aux (:id opgm))
		   (add-clean pgm)))
	     (add-clean pgm)))))))
  (defn add [^Pgm pgm] (.execute pool #(add1 pgm))))
