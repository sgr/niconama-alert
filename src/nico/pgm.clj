;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコニコ生放送の番組情報とその操作"}
  nico.pgm
  (:use [clojure.set :only [union]])
  (:require [time-utils :as tu]
	    [log-utils :as lu]))

(def *scale* 1.05) ;; 最大保持数

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
			#(let [d (tu/diff (:pubdate %2) (:pubdate %1))]
			   (if (= 0 d) (.compareTo (name (:id %2)) (name (:id %1))) d))))
      idx-updated-at (ref (sorted-set-by ;; 取得時刻でソートされた番組IDからなる集合
			   #(let [d (tu/diff (:updated_at %2) (:updated_at %1))]
			      (if (= 0 d) (.compareTo (name (:id %2)) (name (:id %1))) d))))
      idx-elapsed (ref (sorted-set-by ;; 確認済経過時間でソートされた番組IDからなる集合
			#(letfn [(elapsed
				  [pgm] (- (.getTime (:updated_at pgm)) (.getTime (:pubdate pgm))))]
			   (let [d (- (elapsed %2) (elapsed %1))]
			     (if (= 0 d) (.compareTo (name (:id %2)) (name (:id %1))) d)))))
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

  (defn- disj-pgm-idx [aset id]
    (reduce #(disj %1 %2) aset (filter #(= id (:id %)) aset)))
  (defn- conj-pgm-idx [aset pgm]
    (conj (disj-pgm-idx aset (:id pgm)) pgm))
  (defn- rem-aux [^clojure.lang.Keyword id]
    (when-let [pgm (get @id-pgms id)]
      (alter idx-elapsed disj-pgm-idx id)
      (alter idx-updated-at disj-pgm-idx id)
      (alter idx-pubdate disj-pgm-idx id)
      (when-let [cid (:comm_id pgm)] (alter idx-comm dissoc cid))
      (alter id-pgms dissoc id)))
  (defn- add-aux2 [^Pgm pgm]
    (alter id-pgms assoc (:id pgm) pgm)
    (when-let [cid (:comm_id pgm)] (alter idx-comm assoc cid pgm))
    (alter idx-pubdate conj-pgm-idx pgm)
    (alter idx-updated-at conj-pgm-idx pgm)
    (alter idx-elapsed conj-pgm-idx pgm))
  (defn- add-aux [^Pgm pgm]
    (let [id (:id pgm) cid (:comm_id pgm)]
      (if-let [opgm (get @idx-comm cid)]
	(when (and (not (= id (:id opgm)))
		   (tu/later? (:pubdate pgm) (:pubdate opgm)))
	  (do (rem-aux (:id opgm))
	      (add-aux2 pgm)))
	(add-aux2 pgm))))
  (defn- update-aux [^Pgm pgm]
    (let [id (:id pgm)
	  orig (get @id-pgms id)]
      (letfn [(updated-time?
	       [k] (and orig (not (= 0 (.compareTo (get orig k) (get pgm k))))))
	      (update-idx [ref] (alter ref conj-pgm-idx pgm))]
	(alter id-pgms assoc id pgm)
	(when (updated-time? :pubdate) (update-idx idx-pubdate))
	(when (updated-time? :updated_at) (update-idx idx-updated-at))
	(when (or (updated-time? :pubdate) (updated-time? :updated_at))
	  (update-idx idx-elapsed)))))
  (defn update [^Pgm pgm]
    (when (get @id-pgms (:id pgm)) (dosync (update-aux pgm))))
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
  (defn- check-consistency []
    (let [npgms (count-pgms)
	  ncomm (count-comm)
	  npubdate (count-pubdate)
	  nupdated (count-updated-at)
	  nelapsed (count-elapsed)]
      ;; 公式などコミュニティIDがついていない放送もあるためncommでは比較しない。
      (when-not (= npgms npubdate nupdated nelapsed)
	(do
	  (println (format " PGMS:    %d" npgms))
	  (println (format " COMM:    %d" ncomm))
	  (println (format " PUBDATE: %d" npubdate))
	  (println (format " UPDATED: %d" nupdated))
	  (println (format " ELAPSED: %d" nelapsed))
	  (println "--------")))))
  (defn- rem-pgms-without-aux [ids]
    (doseq [id (filter #(not (contains? ids %)) (keys @id-pgms))] (rem-aux id)))
  (defn- clean-old-aux []
    (when (< 0 @total)
      (let [c (int (* *scale* @total))] ;; 総番組数の1.2倍までは許容
	(when (< c (count @id-pgms))
	  (let [now (tu/now)
		updated (set
			 (map #(:id %)
			      (take-while #(tu/within? (:updated_at %) now 300)
					  @idx-updated-at)))]
	    ;; 5分以内に存在が確認された番組は多くとも残す
	    (if (<= c (count updated))
	      (do
		(println (format " rem-pgms-without-aux updated (%d <= %d)"
				 c (count updated)))
		(rem-pgms-without-aux updated))
	      (let [with-pubdate (union updated
					(set
					 (map #(:id %)
					      (take-while #(tu/within? (:pubdate %) now 1800)
							  @idx-pubdate))))]
		;; 30分以内に開始された番組は多くとも残す
		(if (<= c (count with-pubdate))
		  (do
		    (println (format " rem-pgms-without-aux pubdate (%d <= %d)"
				     c (count with-pubdate)))
		    (rem-pgms-without-aux with-pubdate))
		  (let [c2 (- c (count with-pubdate))
			with-elapsed (union with-pubdate
					    (set
					     (map #(:id %)
						  (take c2 (filter #(not (contains? with-pubdate %))
								   @idx-elapsed)))))]
		    (if (<= c (count with-elapsed))
		      (do
			(println (format " rem-pgms-without-aux elapsed (%d <= %d)"
					 c (count with-elapsed)))
			(rem-pgms-without-aux with-elapsed))
		      (println (format " don't rem-pgms-without elapsed (%d > %d)"
				       c (count with-elapsed)))))))))))))
  (defn add [^Pgm pgm]
    (dosync
     (if (contains? @id-pgms (:id pgm))
       (update-aux (merge-pgm pgm))
       (add-aux pgm))
     (clean-old-aux))
    (check-consistency)
    (call-hook-updated)))
