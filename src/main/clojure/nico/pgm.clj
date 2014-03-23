;; -*- coding: utf-8-unix -*-
(ns nico.pgm)

(defrecord Pgm
  [id		;; 番組ID
   title	;; タイトル
   open_time	;; 開場時刻
   start_time	;; 開演時刻
   description  ;; 説明
   category	;; カテゴリ
   link		;; 番組へのリンク
   thumbnail	;; コミュニティのサムネイル
   owner_name	;; 放送者名
   member_only	;; コミュ限
   type		;; community or channel
   comm_name	;; コミュニティ名
   comm_id	;; コミュニティID
   fetched_at	;; 取得時刻
   updated_at])	;; 更新時刻
