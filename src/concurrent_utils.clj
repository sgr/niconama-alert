;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "並行処理ユーティリティ"}
  concurrent-utils
  (:use [clojure.tools.logging])
  (:import [java.util.concurrent LinkedBlockingQueue ThreadPoolExecutor TimeUnit]))

(def ^{:private true} KEEP-ALIVE 5) ; コアスレッド数を超えた処理待ちスレッドを保持する時間(秒)

(defn ^ThreadPoolExecutor periodic-executor
  "size: スレッド数
   unit: 時間単位
   interval: 実行間隔"
  [^long size ^TimeUnit unit ^long interval]
  (let [queue (LinkedBlockingQueue.)]
    [queue
     (proxy [ThreadPoolExecutor] [0 size KEEP-ALIVE TimeUnit/SECONDS queue]
       (afterExecute
         [r e]
         (proxy-super afterExecute r e)
         (when e (error e "failed execution"))
         (.sleep unit interval)))]))
