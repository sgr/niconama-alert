;; -*- coding: utf-8-unix -*-
(ns nico.ui.channel-panel
  (:require [clojure.string :as s]
            [clojure.tools.logging :as log]
            [nico.db :as db]
            [slide.core :as slc]
            [seesaw.bind :as sb]
            [seesaw.core :as sc]
            [seesaw.mig :as sm])
  (:import [nico.ui PgmList]
           [java.awt.event MouseWheelListener]))

(defmacro add-horizontal-wheel-event-dispatcher [source parent]
  `(-> ~source
       (.addMouseWheelListener
        (proxy [MouseWheelListener][]
          (mouseWheelMoved [e#]
            (when (.isShiftDown e#)
              (.dispatchEvent ~parent e#)))))))

(defn panel [id title]
  (when (and id title)
    (let [c-alert  (sc/checkbox :id :c-alert :text "Alert")
          btn-gear (sc/button :id :btn-gear :icon "gear.png")
          btn-menu (sc/button :id :btn-menu :icon "menu.png")
          control  (sm/mig-panel
                    :id :control
                    :constraints ["wrap 3, fillx, ins 0 10 0 10" "[:170:][:30:][:30:]" ""]
                    :border title
                    :items [[c-alert "grow"] [btn-gear] [btn-menu]])
          stbl (sc/scrollable (doto (PgmList.) (sc/config! :id :lst)))
          channel-panel (sc/border-panel :id id :north control :center stbl)]

      (add-horizontal-wheel-event-dispatcher control channel-panel)
      (add-horizontal-wheel-event-dispatcher stbl channel-panel)

      channel-panel)))

(defn menu [type idx last-idx]
  (sc/with-widgets [(sc/menu-item :id :move-left :text "Move Left")
                    (sc/menu-item :id :move-right :text "Move Right")
                    (sc/menu-item :id :relogin :text "Re-login")
                    (sc/menu-item :id :dispose :text "Dispose")]
    (cond
     (= 0 last-idx)   (do (sc/config! move-left :enabled? false)
                          (sc/config! move-right :enabled? false))
     (= 0 idx)        (do (sc/config! move-left :enabled? false)
                          (sc/config! move-right :enabled? true))
     (= idx last-idx) (do (sc/config! move-left :enabled? true)
                          (sc/config! move-right :enabled? false)))
    (sc/popup :items
              (condp = type
                :comm [move-left move-right (sc/separator) relogin (sc/separator) dispose]
                :kwd  [move-left move-right (sc/separator) dispose]
                nil))))

(defn dlg-comm [data]
  (let [new-data (agent data)]
    (sc/with-widgets
      [(sc/text :id :email)
       (sc/password :id :passwd)
       (slc/dialog
        :id :dlg
        :title "Login info"
        :content (sm/mig-panel
                  :constraints ["wrap 2, ins 10 10 10 10" "[:50:][:400:]"]
                  :items [["Email:"][email "grow"]
                          ["Password:"][passwd "grow"]])
        :success-fn (fn [_] (merge data @new-data))
        :option-type :ok-cancel
        :id-ok :btn-ok)]

      (sb/bind
       (sb/funnel email passwd)
       (sb/b-send
        new-data
        (fn [old-spec [email passwd]]
          (when (and (pos? (count email)) (pos? (count passwd)))
            {:email email :passwd passwd}))))
      (let [{:keys [btn-ok]} (sc/group-by-id dlg)]
        (sb/bind new-data
                 (sb/transform #(and % (not= (select-keys data [:email :passwd]) %)))
                 (sb/property btn-ok :enabled?)))

      ;; set values
      (sc/value! email (:email data))
      (sc/value! passwd (:passwd data))

      (sc/pack! dlg))))

(defn dlg-kwd-title [data]
  (sc/with-widgets
    [(sc/text :id :title)
     (slc/dialog
      :id :dlg
      :title "Enter title"
      :content (sm/mig-panel
                :constraints ["wrap 2, ins 10 10 10 10" "[:50:][:400:]"]
                :items [["Title:"][title "grow"]])
      :success-fn (fn [_] (assoc data :title (sc/value title)))
      :option-type :ok-cancel
      :id-ok :btn-ok)]

    (let [{:keys [btn-ok]} (sc/group-by-id dlg)]
      (sb/bind title
               (sb/transform #(and % (pos? (count %))))
               (sb/property btn-ok :enabled?)))

    (sc/pack! dlg)))

(defn dlg-kwd [data]
  (let [new-data (agent data)]
    (sc/with-widgets
      [(sc/text :id :title-text)
       (sc/checkbox :id :c-title :text "Title")
       (sc/checkbox :id :c-desc  :text "Description")
       (sc/checkbox :id :c-owner :text "Owner")
       (sc/checkbox :id :c-cat   :text "Category")
       (sc/checkbox :id :c-comm  :text "Community")
       (sc/text :id :cond-text :multi-line? true :wrap-lines? true :rows 3 :editable? true)
       (slc/dialog
        :id :dlg
        :title "Search settings"
        :content (sm/mig-panel
                  :constraints ["wrap 1, ins 10 10 10 10" "[:400:]"]
                  :items [[(sm/mig-panel
                            :border "Title"
                            :constraints ["fill, ins 10 15 10 15"]
                            :items [[title-text "grow"]])
                           "grow"]
                          [(sm/mig-panel
                            :border "Search target"
                            :constraints ["wrap 3, ins 10 15 10 15" "[33%][33%][33%]"]
                            :items [[c-title][c-desc][c-owner][c-cat][c-comm]])
                           "grow"]
                          [(sm/mig-panel
                            :border "Search condition"
                            :constraints ["fill, ins 10 10 10 10"]
                            :items [[(sc/scrollable cond-text) "growx, height 100"]])
                           "grow"]])
        :success-fn (fn [_] (merge data @new-data))
        :option-type :ok-cancel
        :id-ok :btn-ok)]

      (sb/bind
       (sb/funnel
        title-text c-title c-desc c-owner c-cat c-comm
        (sb/bind cond-text (sb/transform #(when (db/where-clause %) %))))
       (sb/b-send
        new-data
        (fn [old-spec vals]
          (let [[title-text title description owner cat comm cond-text] vals]
            (when (and (not (s/blank? title-text))
                       (or title description owner cat comm)
                       (not (s/blank? cond-text)))
              {:title title-text
               :target (->> [(when title :title) (when description :description) (when owner :owner_name)
                             (when cat :category) (when comm :comm_name)]
                            (filter identity)
                            set)
               :query cond-text})))))

      (let [{:keys [btn-ok]} (sc/group-by-id dlg)]
        (sb/bind new-data
                 (sb/transform #(do (log/debugf "old-data: %s" (pr-str (select-keys data [:title :target :query])))
                                    (log/debugf "new-data: %s" (pr-str %))
                                    (and % (not= (select-keys data [:title :target :query]) %))))
                 (sb/property btn-ok :enabled?)))

      ;; set values
      (let [tgts (:target data)]
        (sc/value! title-text (:title data))
        (sc/value! c-title    (contains? tgts :title))
        (sc/value! c-desc     (contains? tgts :description))
        (sc/value! c-owner    (contains? tgts :owner_name))
        (sc/value! c-cat      (contains? tgts :category))
        (sc/value! c-comm     (contains? tgts :comm_name))
        (sc/value! cond-text  (:query data)))

      (sc/pack! dlg))))
