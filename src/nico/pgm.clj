;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコニコ生放送の番組情報とその操作"}
  nico.pgm
  (:use [clojure.set :only [union]])
  (:require [time-utils :as tu]
	    [log-utils :as lu]))

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
      idx-pubdate (ref (sorted-set-by ;; 開始時刻でソートされた番組IDからなるリスト
			#(let [pgm1 (get @id-pgms %1), pgm2 (get @id-pgms %2)]
			   (cond
			    (and pgm1 pgm2) (tu/later? (:pubdate pgm1) (:pubdate pgm2))
			    (nil? pgm1) 1
			    (nil? pgm2) -1))))
      idx-updated-at (ref (sorted-set-by ;; 取得時刻でソートされた番組IDからなるリスト
			   #(let [pgm1 (get @id-pgms %1), pgm2 (get @id-pgms %2)]
			      (cond
			       (and pgm1 pgm2) (tu/later? (:updated_at pgm1) (:updated_at pgm2))
			       (nil? pgm1) 1
			       (nil? pgm2) -1))))
      idx-elapsed (ref (sorted-set-by ;; 確認済経過時間でソートされた番組IDからなるリスト
			#(letfn [(elapsed [id]
					  (if-let [pgm (get @id-pgms id)]
					    (- (.getTime (:updated_at pgm)) (.getTime (:pubdate pgm)))
					    (do
					      (print (format "idx-elapsed[s]: %s (%s / %s) %s"
							     id
							     (contains? @idx-pubdate id)
							     (contains? @idx-updated-at id)
							     (contains? @id-pgms id)))
					      -10000)))]
			   (> (elapsed %1) (elapsed %2)))))
      last-updated (ref (tu/now)) ;; 番組情報の最終更新時刻
      hook-updated (ref '()) ;; 番組集合の更新を報せるフック
      called-at-hook-updated (ref (tu/now))] ;; フックを呼び出した最終時刻
  (defn pgms [] @id-pgms)
  (defn count-pgms [] (count @id-pgms))
  (defn- count-comm [] (count @idx-comm))
  (defn- count-pubdate [] (count @idx-pubdate))
  (defn- count-updated-at [] (count @idx-updated-at))
  (defn- count-elapsed [] (count @idx-elapsed))
  (defn add-hook [kind f]
    (condp = kind
	:updated (dosync (alter hook-updated conj f))))
  (defn get-last-updated [] last-updated)
  (defn- call-hook-updated []
    (when-not (tu/within? @called-at-hook-updated (tu/now) 3)
      (dosync
       (doseq [f @hook-updated] (f))
       (ref-set called-at-hook-updated (tu/now)))))
  (defn set-total [t]
    (do (reset! total t)
	(call-hook-updated)))
  (defn get-total [] @total)
  (defn get-pgm [^String id] (get @id-pgms id))

  (defn- v-elapsed [pgms]
    (partial every? #(if-let [pgm (get pgms %)]
		       (and (:pubdate pgm) (:updated_at pgm))
		       (do (println (format "idx-elapsed[v]: %s %s" % (contains? pgms %)))
			   false))))
  (defn- v-updated-at [pgms]
    (partial every? #(if-let [pgm (get pgms %)]
		       (:updated_at pgm)
		       (do (println (format "idx-updated-at[v]: %s %s" % (contains? pgms %)))
			   false))))
  (defn- v-pubdate [pgms]
    (partial every? #(if-let [pgm (get pgms %)]
		       (:pubdate pgm)
		       (do (println (format "idx-pubdate[v]: %s %s" % (contains? pgms %)))
			   false))))
  (defn- v-comm [pgms]
    (partial every? #(let [[cid id] %]
		       (if-let [pgm (get pgms id)]
			 (= cid (:comm_id pgm))
			 (do (println (format "idx-comm[v]: %s / %s / %s"
					      cid id (contains? pgms id)))
			     false)))))
  ;; -aux内ではdosyncは使わず、その外でdosyncで囲むようにする。
  (defn- rem-aux [^String id]
    (print (format " REMOVING: %s ..." id))
    (dosync
     (let [eid-pgms (ensure id-pgms)
	   pgm (get @id-pgms id) cid (if pgm (:comm_id pgm) nil)]
       (ref-set idx-elapsed
		(disj (with-meta @idx-elapsed {:validator (v-elapsed eid-pgms)}) id))
       (ref-set idx-updated-at
		(disj (with-meta @idx-updated-at {:validator (v-updated-at eid-pgms)}) id))
       (ref-set idx-pubdate
		(disj (with-meta @idx-pubdate {:validator (v-pubdate eid-pgms)}) id))
       (when cid
	 (ref-set idx-comm
		  (dissoc (with-meta @idx-comm {:validator (v-comm eid-pgms)}) cid)))
       (alter id-pgms dissoc id)))
    (println " DONE"))
;;    (println (format "  contains? %s -> %s / %s / %s"
;;		     id (contains? @idx-elapsed id) (contains? @idx-updated-at id)
;;		     (contains? @idx-pubdate id))))
  (defn- add-aux2 [^Pgm pgm]
    (print (format " ADDING: %s ..." (:id pgm)))
    (dosync
     (let [id (:id pgm) cid (:comm_id pgm)]
       (alter id-pgms assoc id pgm)
       (let [eid-pgms (ensure id-pgms)]
	 (when cid
	   (ref-set idx-comm
		    (assoc (with-meta @idx-comm {:validator (v-comm eid-pgms)}) cid id)))
	 (ref-set idx-pubdate
		  (conj (with-meta @idx-pubdate {:validator (v-pubdate eid-pgms)}) id))
	 (ref-set idx-updated-at
		  (conj (with-meta @idx-updated-at {:validator (v-updated-at eid-pgms)}) id))
	 (ref-set idx-elapsed
		  (conj (with-meta @idx-elapsed {:validator (v-elapsed eid-pgms)}) id)))))
    (println " DONE"))
  (defn- add-aux [^Pgm pgm]
    (let [id (:id pgm) cid (:comm_id pgm)]
      (if-let [oid (get @idx-comm cid)]
	(let [opgm (get @id-pgms oid)]
	  (when (and (not (= id oid))
		     (tu/later? (:pubdate pgm) (:pubdate opgm)))
	    (do (rem-aux oid)
		(add-aux2 pgm))))
	(add-aux2 pgm))))
  (defn- update-aux [^Pgm pgm]
    (dosync
     (let [orig (get @id-pgms (:id pgm))]
       (letfn [(updated-time?
		[k] (and orig (not (= 0 (.compareTo (get orig k) (get pgm k))))))
	       (update-idx [ref id] (alter ref conj id))]
	 (alter id-pgms assoc (:id pgm) pgm)
	 (let [eid-pgms (ensure id-pgms)] ;; TODO 後でちゃんと処理をかくこと
	   (when (updated-time? :pubdate) (update-idx idx-pubdate (:id pgm)))
	   (when (updated-time? :updated_at) (update-idx idx-updated-at (:id pgm)))
	   (when (or (updated-time? :pubdate) (updated-time? :updated_at)
		     (update-idx idx-elapsed (:id pgm)))))))))
  (defn update [^Pgm pgm]
    (when (get @id-pgms (:id pgm)) (update-aux pgm)))
  (defn- merge-pgm [^Pgm pgm]
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
	(Pgm. (:id orig)
	      (longer-for :title pgm orig)
	      (later-for :pubdate pgm orig)
	      (longer-for :desc pgm orig)
	      (:category orig)
	      (:link orig)
	      (:thumbnail orig)
	      (:owner_name orig)
	      (:member_only orig)
	      (:type orig)
	      (longer-for :comm_name pgm orig)
	      (:comm_id orig)
	      (or (:alerted orig) (:alerted pgm))
	      (:fetched_at orig)
	      (tu/now)))
      pgm))
  (defn- rem-pgms-without-aux [ids]
    (doseq [id (keys @id-pgms)]
      (when-not (contains? ids id) (rem-aux id))))
  (defn- clean-old-aux []
    (when (< 0 @total)
      (let [c (int (* 1.2 @total))] ;; 総番組数の1.2倍までは許容
	(when (< c (count (keys @id-pgms)))
	  (let [now (tu/now)
		confirmed (set (take-while #(tu/within? (:updated_at (get @id-pgms %)) now 1800)
					   @idx-updated-at))]
	    ;; 30分以内に存在が確認された番組は多くとも残す
	    (if (<= c (count confirmed))
	      (rem-pgms-without-aux confirmed)
	      (let [with-young (union confirmed
				      (set
				       (take-while #(tu/within? (:pubdate (get @id-pgms %)) now 1800)
						   @idx-pubdate)))]
		(if (<= c (count with-young))
		  (rem-pgms-without-aux with-young)
		  (let [c2 (- c (count with-young))
			with-elder (union with-young
					  (set (take c2 (filter #(not (contains? with-young %))
								@idx-elapsed))))]
		    (rem-pgms-without-aux with-elder))))))))))
  (defn add [^Pgm pgm]
    (if (contains? @id-pgms (:id pgm))
      (update-aux (merge-pgm pgm))
      (add-aux pgm))
    (clean-old-aux)
    (call-hook-updated)))
      
