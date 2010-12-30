;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコニコ生放送の番組情報とその操作"}
  nico.pgm
  (:use [time-utils :only [earlier?]]))

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
(let [id-pgms (ref {}),		;; 番組IDをキー、番組情報を値とするマップ
      comm-pgms (ref {}),	;; コミュニティIDをキー、番組情報を値とするマップ
      old (ref #{})]		;; 取得済み番組IDの集合。番組IDをキー、取得回数を値とするマップ
  (defn pgms [] @id-pgms)
  (defn count-pgms [] (count @id-pgms))
  (defn new? [^String id] (not (contains? @old id)))
  (defn- is-to-add?
    "この番組情報を加えるべきか？同じコミュニティの放送が複数あったら、新しいものだけを追加する。"
    [^Pgm pgm]
    (if (= "community" (:type pgm))
      (if-let [apgm (get @comm-pgms (:comm_id pgm))]
	(earlier? (:pubdate apgm) (:pubdate pgm))
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
    (if (> 1800000 (elapsed-time pgm)) true false))
  (defn rem-pgms-partial
    "部分的に取得された番組情報を基に、pgmsから不要そうな番組情報を削除する。
     番組情報を全て取得しないと終了した番組情報を削除できないと、
     繁忙期はサーバーも負荷が高く全ての番組を取得することが困難であるため、
     中々全ての番組情報を取得出来無い状態が続き、結果として番組情報が総番組数の２倍を超えることもある。
     その結果ヒープ領域が必要以上に消費されることにもなるため、
     なんとかしてこのような繁忙期であっても終了した番組を推定するヒューリスティックスを適用する。"
    [ids total]
    (when (> (count-pgms) total)	;; 取得番組数が総番組数より少いうちはなにもしない
      ;; 取得された番組は残す
      (let [fetched-pgms (select-keys @id-pgms ids)]
	(println (format "fetched-pgms: %d" (count fetched-pgms)))
	(if (> total (count fetched-pgms))
	  ;; 開始から30分経ってない番組は残す
	  (let [younger-pgms (select-keys @id-pgms
					  (for [[id pgm] @id-pgms :when
						(and (not (contains? ids id))
						     (younger? pgm))] id))]
	    (println (format " younger-pgms: %d" (count younger-pgms)))
	    (if (> total (+ (count fetched-pgms) (count younger-pgms)))
	      ;; 延長してる番組を延長時間の長い順に残す
	      (let [rest (- total (+ (count fetched-pgms) (count younger-pgms)))
		    oids (into (keys fetched-pgms) (keys younger-pgms))
		    sids (sort #(> (elapsed-time (get @id-pgms %1))
				   (elapsed-time (get @id-pgms %2)))
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
