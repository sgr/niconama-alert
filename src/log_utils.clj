;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "ログやエラー処理に関する操作"}
  log-utils
  )

(defn printe
  [^String s ^Exception e]
  (println (format " %s: %s: %s" s (-> e .getClass .getName) (.getMessage e)))
  (.printStackTrace e))

