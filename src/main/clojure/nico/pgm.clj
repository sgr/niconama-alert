;; -*- coding: utf-8-unix -*-
(ns nico.pgm)

(defrecord Pgm
  [^String id		;; 番組ID
   ^String title	;; タイトル
   ^long   open_time	;; 開場時刻
   ^long   start_time	;; 開演時刻
   ^String description  ;; 説明
   ^String category	;; カテゴリ
   ^String link		;; 番組へのリンク
   ^String thumbnail	;; コミュニティのサムネイル
   ^String owner_name	;; 放送者名
   ^int    member_only	;; コミュ限なら1, そうでなければ0
   ^int    type 	;; community: 0, channel: 1, official: 2
   ^String comm_name	;; コミュニティ名
   ^String comm_id	;; コミュニティID
   ^long   fetched_at	;; 取得時刻
   ^long   updated_at])	;; 更新時刻
