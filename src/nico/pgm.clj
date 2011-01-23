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
   view		;; 来場者数
   type		;; community or channel
   num_res	;; コメント数
   comm_name	;; コミュニティ名
   comm_id	;; コミュニティID
   alerted	;; アラート済み
   fetched_at])	;; 番組取得時刻

;; 以下で使われるpgmsとは、番組IDをキー、pgmを値とするようなマップである。
(let [id-pgms (ref {})		;; 番組IDをキー、番組情報を値とするマップ
      comm-pgms (ref {})	;; コミュニティIDをキー、番組情報を値とするマップ
      old (ref #{})		;; 取得済み番組IDの集合。番組IDをキー、取得回数を値とするマップ
      last-updated (ref (tu/now))]	;; 番組情報の最終更新時刻
  (defn pgms [] @id-pgms)
  (defn count-pgms [] (count @id-pgms))
  (defn new? [^String id] (not (contains? @old id)))
  (defn- clear-pgms []
    (dosync
     (ref-set id-pgms {})
     (ref-set comm-pgms {})
     (ref-set old #{}))
    (System/gc))
  (defn- update-updated
    "最終更新時刻を更新する。もし前の最終更新より時間が経ちすぎていたら、番組情報をクリアする。"
    []
    (let [now (tu/now)]
      (when (< 1800000 (- (.getTime now) (.getTime @last-updated)))
	(clear-pgms))
      (dosync
       (ref-set last-updated now))))
  (defn- is-to-add?
    "この番組情報を加えるべきか？同じコミュニティの放送が複数あったら、新しいものだけを追加する。"
    [^Pgm pgm]
    (if (= "community" (:type pgm))
      (if-let [apgm (get @comm-pgms (:comm_id pgm))]
	(tu/earlier? (:pubdate apgm) (:pubdate pgm))
	true)
      true))
  (defn get-pgm
    "番組情報を得る"
    [^String id] (get @id-pgms id nil))
  (defn- rem-pgm
    "番組情報を削除する"
    [^String id]
    (dosync
     (alter comm-pgms dissoc (:comm_id (get @id-pgms id)))
     (alter id-pgms dissoc id)))
  (defn- add-pgm
    "番組情報を追加する"
    [^Pgm pgm]
    (when (is-to-add? pgm)
      (dosync
       (when-let [old-pgm (get @comm-pgms (:comm_id pgm))] (rem-pgm (:id old-pgm)))
       (alter id-pgms assoc (:id pgm) pgm)
       (when-let [cid (:comm_id pgm)] (alter comm-pgms assoc cid pgm)))))
  (defn update-pgm
    "番組情報を更新する"
    [^Pgm pgm]
    (dosync
     (alter id-pgms assoc (:id pgm) pgm)
     (when-let [cid (:comm_id pgm)] (alter comm-pgms assoc cid pgm))))
  (defn rem-pgms
    "複数の番組を削除する"
    [ids]
    (doseq [id ids] (rem-pgm id)))
  (defn add-pgms
    "複数の番組を追加する"
    [pgms]
    (update-updated)
    (doseq [pgm pgms] (add-pgm pgm)))
  (defn reset-pgms
    "全ての番組情報を与えられた番組情報集合に置き換える"
    [pgms]
    (println (format " reset-pgms: %d" (count pgms)))
    (dosync
     (ref-set id-pgms (reduce #(apply assoc %1 %2) {} (for [pgm pgms] (list (:id pgm) pgm))))
     (ref-set comm-pgms (reduce #(apply assoc %1 %2) {}
				(for [pgm pgms :when (:comm_id pgm)] (list (:comm_id pgm) pgm))))))
  (defn rem-pgms-without
    "取得済み放送情報を基に、pgmsから不要な番組情報を削除する"
    [ids]
    (reset-pgms (vals (select-keys @id-pgms ids))))
  (defn- elapsed-time
    [pgm]
    (- (.getTime (:fetched_at pgm)) (.getTime (:pubdate pgm))))
  (defn- younger?
    "番組開始30分以内なら真"
    [pgm]
    (if (> 1800000 (- (.getTime (tu/now)) (.getTime (:pubdate pgm)))) true false))
  (defn- extend-score
    "今も延長している可能性を評価する"
    [pgm now]
    ;; 今は、取得時点での番組放送時間から、取得後から今までの経過時間を引いた時間をスコアとしている。
    (- (elapsed-time pgm) (- (.getTime (tu/now)) (.getTime (:fetched_at pgm)))))
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
  (defn update-old
    "現在取得済みの番組のIDをoldにセットする(oldを更新する)"
    [] (dosync (ref-set old (set (for [[id pgm] @id-pgms] id))))))
