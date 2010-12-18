;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ニコニコ生放送の番組情報とその操作"}
  nico.pgm
  (:use [time-utils :only [earlier?]]))

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

;; 以下で使われるpgmsとは、番組IDをキー、pgmを値とするようなマップである。
(let [id-pgms (ref {}),		;; 番組IDをキー、番組情報を値とするマップ
      comm-pgms (ref {}),	;; コミュニティIDをキー、番組情報を値とするマップ
      old (ref #{})		;; 取得済み番組IDの集合
      cnt (ref 0)]		;; プログラム数
  (defn pgms [] @id-pgms)
  (defn count-pgms [] @cnt)
  (defn new? [id] (not (contains? @old id)))
  (defn- is-to-add?
    "この番組情報を加えるべきか？同じコミュニティの放送が複数あったら、新しいものだけを追加する。
     既に同じIDの情報があれば追加しない。"
    [pgm]
    (if-not (get @id-pgms (:id pgm) false)
      (if-let [apgm (get @comm-pgms (:comm_id pgm))]
	(earlier? (:pubdate apgm) (:pubdate pgm))
	true)
      false))
  (defn get-pgm
    "番組情報を得る"
    [id] (get @id-pgms id nil))
  (defn- rem-pgm
    "番組情報を削除する"
    [id]
    (dosync
     (alter comm-pgms dissoc (:comm_id (get @id-pgms id)))
     (alter id-pgms dissoc id)))
  (defn- add-pgm
    "番組情報を追加する"
    [pgm]
    (when (is-to-add? pgm)
      (dosync
       (when-let [old-pgm (get @comm-pgms (:comm_id pgm))] (rem-pgm (:id old-pgm)))
       (alter id-pgms assoc (:id pgm) pgm)
       (when-let [cid (:comm_id pgm)] (alter comm-pgms assoc cid pgm)))))
  (defn update-pgm
    "番組情報を更新する"
    [pgm]
    (dosync
     (alter id-pgms assoc (:id pgm) pgm)
     (when-let [cid (:comm_id pgm)] (alter comm-pgms assoc cid pgm))))
  (defn rem-pgms
    "複数の番組を削除する"
    [ids]
    (doseq [id ids] (rem-pgm id))
    (dosync (ref-set cnt (count @id-pgms))))
  (defn add-pgms
    "複数の番組を追加する"
    [pgms]
    (doseq [pgm pgms] (add-pgm pgm))
    (dosync (ref-set cnt (count @id-pgms))))
  (defn reset-pgms
    "全ての番組情報を与えられた番組情報集合に置き換える"
    [pgms]
    (dosync
     (ref-set id-pgms (reduce #(apply assoc %1 %2) {} (for [pgm pgms] (list (:id pgm) pgm))))
     (ref-set comm-pgms (reduce #(apply assoc %1 %2) {}
				(for [pgm pgms :when (:comm_id pgm)] (list (:comm_id pgm) pgm))))
     (ref-set cnt (count @id-pgms))))
  (defn rem-pgms-without
    "取得済み放送情報を基に、pgmsから不要な番組情報を削除する"
    ([ids]
       (reset-pgms (vals (select-keys @id-pgms ids))))
    ([ids date]
       (reset-pgms (vals (select-keys @id-pgms (for [[id pgm] @id-pgms :when
						     (or (earlier? (:pubdate pgm) date)
							 (contains? ids id))] id))))))
  (defn update-old
    "現在取得済みの番組のIDをoldにセットする(oldを更新する)"
    [] (dosync (ref-set old (set (for [[id pgm] @id-pgms] id))))))
