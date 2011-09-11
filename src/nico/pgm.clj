;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコニコ生放送の番組情報とその操作"}
  nico.pgm
  (:require [time-utils :as tu]))

(defrecord Pgm
  [id		;; 番組ID
   title	;; タイトル
   pubdate	;; 開始日時
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
   fetched_at])	;; 番組取得時刻

;; 以下で使われるpgmsとは、番組IDをキー、pgmを値とするようなマップである。
(let [id-pgms (ref {})		;; 番組IDをキー、番組情報を値とするマップ
      comm-pgms (ref {})	;; コミュニティIDをキー、番組情報を値とするマップ
      new (ref {})		;; 新しい番組IDの集合。キーは番組ID。
      last-updated (ref (tu/now))	;; 番組情報の最終更新時刻
      total (atom 0)		;; 
      hook-updated (ref '()) ;; 番組集合の更新を報せるフック
      called-at-hook-updated (ref (tu/now))] ;; フックを呼び出した最終時刻
  (defn add-hook [kind f]
    (condp = kind
	:updated (dosync (alter hook-updated conj f))))
  (defn get-last-updated [] last-updated)
  (defn- call-hook-updated []
    (when (< 3000 (- (.getTime (tu/now)) (.getTime @called-at-hook-updated)))
      (dosync
       (doseq [f @hook-updated] (f))
       (ref-set called-at-hook-updated (tu/now)))))
  (defn pgms [] @id-pgms)
  (defn count-pgms [] (count @id-pgms))
  (defn new? [^String id] (contains? @new id))
  (defn set-total [t]
    (do (reset! total t)
	(call-hook-updated)))
  (defn get-total [] @total)
  (defn- clear-pgms []
    (dosync
     (ref-set id-pgms {})
     (ref-set comm-pgms {})
     (ref-set new #{}))
    (call-hook-updated))
  (defn get-pgm
    "番組情報を得る"
    [^String id] (get @id-pgms id nil))
  (defn- is-to-add?
    "この番組情報を加えるべきか？同じコミュニティの放送が複数あったら、新しいものだけを追加する。"
    [^Pgm pgm]
    (if-not (get-pgm (:id pgm))
      (if (= "community" (:type pgm))
	(if-let [apgm (get @comm-pgms (:comm_id pgm))]
	  (do
	    (when (or (nil? (:pubdate apgm)) (nil? (:pubdate pgm)))
	      (println (format "apgm: %s, pgm: %s, title: %s"
			       (:pubdate apgm) (:pubdate pgm) (:title pgm))))
	    ;; スクレイプで得た開始時刻は秒の情報が落ちてしまうため、60秒以内なら無視する。
	    (if (tu/within? (:pubdate apgm) (:pubdate pgm) 60)
	      false
	      (tu/earlier? (:pubdate apgm) (:pubdate pgm))))
	  true)
	true)
      false))
  (defn rem-pgm
    "番組情報を削除する"
    [^String id]
    (dosync
     (alter comm-pgms dissoc (:comm_id (get @id-pgms id)))
     (alter id-pgms dissoc id)
     (ref-set last-updated (tu/now)))
    (call-hook-updated))
  (defn add-pgm
    "番組情報を追加する"
    [^Pgm pgm]
    (when (is-to-add? pgm)
      (dosync
       (when-let [old-pgm (get @comm-pgms (:comm_id pgm))] (rem-pgm (:id old-pgm)))
       (alter id-pgms assoc (:id pgm) pgm)
       (alter new assoc (:id pgm) pgm)
       (when-let [cid (:comm_id pgm)] (alter comm-pgms assoc cid pgm))
       (ref-set last-updated (tu/now)))
      (call-hook-updated)))
  (defn update-if-pgm
    "与えられた番組IDに対する述語が成り立つ場合のみ番組情報をmで更新する。
     更新した場合はtrue、述語が成り立たないか番組が存在しない場合はfalseを返す。"
    [id pred m]
    (dosync
     (if-let [pgm (get @id-pgms id)]
       (if (pred pgm)
	 (let [apgm (apply assoc pgm (flatten (apply list m)))]
	   (alter id-pgms assoc id apgm)
	   (when-not (= (:comm_id pgm) (:comm_id apgm))
	     (when-let [cid (:comm_id pgm)] (alter comm-pgms dissoc cid))
	     (when-let [cid (:comm_id apgm)] (alter comm-pgms assoc cid apgm)))
	   true)
	 false)
       false)))
  (defn rem-pgms
    "複数の番組を削除する"
    [ids]
    (doseq [id ids] (rem-pgm id)))
  (defn add-pgms
    "複数の番組を追加する"
    [pgms]
    (doseq [pgm pgms] (add-pgm pgm)))
  (defn reset-pgms
    "全ての番組情報を与えられた番組情報集合に置き換える"
    [pgms]
    (println (format " reset-pgms: %d" (count pgms)))
    (dosync
     (ref-set id-pgms (reduce #(apply assoc %1 %2) {} (for [pgm pgms] (list (:id pgm) pgm))))
     (ref-set comm-pgms (reduce #(apply assoc %1 %2) {}
				(for [pgm pgms :when (:comm_id pgm)] (list (:comm_id pgm) pgm))))
     (ref-set last-updated (tu/now)))
    (call-hook-updated))
  (defn rem-pgms-without
    "取得済み放送情報を基に、pgmsから不要な番組情報を削除する"
    [ids]
    (reset-pgms (vals (select-keys @id-pgms ids))))
  (defn- elapsed-time
    [pgm]
    (try
      (- (.getTime (:fetched_at pgm)) (.getTime (:pubdate pgm)))
      (catch Exception e
	(println (format " *** FAILED ELAPSED-PGM: %s %s (%s) [%s-%s]"
			 (:id pgm) (:title pgm) (:link pgm) (:pubdate pgm) (:fetched_at pgm))
	e))))
  (defn- younger?
    "番組開始30分以内なら真"
    [pgm]
    (if (> 1800000 (- (.getTime (tu/now)) (.getTime (:pubdate pgm)))) true false))
  (defn- extend-score
    "今も延長している可能性を評価する"
    [pgm now]
    ;; 今は、取得時点での番組放送時間から、取得後から今までの経過時間を引いた時間をスコアとしている。
    (- (elapsed-time pgm) (- (.getTime now) (.getTime (:fetched_at pgm)))))
  (defn rem-pgms-partial
    "部分的に取得された番組情報を基に、pgmsから不要そうな番組情報を削除する。
     番組情報を全て取得すれば終了した番組を判別し削除することができるのだが、
     繁忙期はサーバーも負荷が高いのか、全ての番組を取得することが困難であるため、
     結果として終了した番組情報も含め、保持する番組情報が総番組数の２倍を超えてしまっていた。
     これを防ぐため、終了した番組を推定するヒューリスティックスを用いて、
     繁忙期であっても番組情報を持ちすぎないようにする。"
    [ids total]
    (when (> (count-pgms) total)	;; 取得番組数が総番組数より少いうちはなにもしない
      ;; 取得された番組は残す
      (let [fetched-pgms (select-keys @id-pgms ids)]
	(println (format "fetched-pgms: %d" (count fetched-pgms)))
	(if (> total (count fetched-pgms))
	  ;; 開始から30分経っていないと思われる番組は残す
	  ;; ※30分以内に終了した番組が残ってしまう可能性がある。
	  (let [younger-pgms (select-keys @id-pgms
					  (for [[id pgm] @id-pgms :when
						(and (not (contains? ids id))
						     (younger? pgm))] id))]
	    (println (format " younger-pgms: %d" (count younger-pgms)))
	    (if (> total (+ (count fetched-pgms) (count younger-pgms)))
	      ;; 延長していると思われる番組を、延長時間の長い順に残す
	      ;; 既に何度も延長している番組は他と比べて今も延長している可能性が高いとみなす。
	      (let [rest (- total (+ (count fetched-pgms) (count younger-pgms)))
		    oids (into ids (keys younger-pgms))
		    now (tu/now)
		    sids (sort #(> (extend-score (get @id-pgms %1) now)
				   (extend-score (get @id-pgms %2) now))
			       (filter #(not (contains? oids %)) (keys @id-pgms)))
		    extended-pgms (select-keys @id-pgms (take rest sids))]
		(println (format " extended-pgms: %d" (count extended-pgms)))
		(reset-pgms (into (vals fetched-pgms)
				  (into (vals younger-pgms) (vals extended-pgms)))))
	      (reset-pgms (into (vals fetched-pgms) (vals younger-pgms)))))
	  (reset-pgms (vals fetched-pgms))))))
  (defn update-new
    "newのうち、更新後1分未満のもののみ残す。"
    []
    (let [now (tu/now)]
      (dosync
       (alter new select-keys
	      (filter
	       (fn [id] (tu/within? (:fetched_at (get @new id)) now 60))
	       (keys @new)))))))

