;; -*- coding: utf-8-unix -*-
(ns nico.ui.pgm-panel-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [slide.core :as slc]
            [seesaw.core :as sc]
            [seesaw.icon :as si])
  (:import [java.util Date]
           [nico.ui PgmPanel]))

(def PGM {"id" "lv12345678"
          "title" "ガラツ八祝言 ガラツ八祝言 ガラツ八祝言 ガラツ八祝言"
          "link" "http://www.aozora.gr.jp/cards/001670/files/55685_53033.html"
          "description" "ガラツ八の八五郎が、その晩聟入をすることになりました。
　祝言の相手は金澤町の酒屋で、この邊では有福の聞えのある多賀屋勘兵衞。嫁はその一粒種で、浮氣つぽいが、綺麗さでは評判の高いお福といふ十九の娘、――これが本當の祝言だと、ガラツ八は十手捕繩を返上して、大店の聟養子に納まるところですが、殘念乍そんなうまいわけには行きません。"
          "owner_name" "野村胡堂"
          "comm_name" "錢形平次捕物控"
          "comm_id" "co9876543"
          "type" (int 0)
          "member_only" true
          "open_time" (Date.)
          "thumbnail" (si/icon "noimage.png")})

(defn- pgm-panel [pgm]
  (PgmPanel/create (get pgm "id") (get pgm "title") (get pgm "link") (get pgm "description")
                   (get pgm "owner_name") (get pgm "comm_name") (get pgm "comm_id")
                   (get pgm "type") (get pgm "member_only") (get pgm "open_time")
                   (get pgm "thumbnail")))

(defn- wait-closing [frame]
  (let [p (promise)]
    (sc/listen frame :window-closing (fn [_] (deliver p true)))
    (-> frame
        slc/move-to-center!
        sc/show!)
    @p))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ^:gui pgmpanel-variable-test
  (wait-closing
   (sc/frame
    :title "testing variable PgmPanel"
    :content (pgm-panel PGM)
    :size [640 :by 480]
    :on-close :dispose)))

(deftest ^:gui pgmpanel-fixed-test
  (wait-closing
   (sc/frame
    :title "testing fixed size PgmPanel"
    :content (doto (pgm-panel PGM) (.setWidth 230) (.setHeight 110))
    :size [640 :by 480]
    :on-close :dispose)))
