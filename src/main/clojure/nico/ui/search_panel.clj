;; -*- coding: utf-8-unix -*-
(ns nico.ui.search-panel
  (:require [clojure.tools.logging :as log]
            [nico.db :as db]
            [seesaw.bind :as sb]
            [seesaw.core :as sc]
            [seesaw.mig :as sm])
  (:import [nico.ui SearchResultPanel]))

(defn panel [spec]
  (let [search-spec (agent spec)
        scrl (javax.swing.JScrollPane.)]
    (sc/with-widgets [(sc/checkbox :id :c-title :text "Title")
                      (sc/checkbox :id :c-desc  :text "Description")
                      (sc/checkbox :id :c-owner :text "Owner")
                      (sc/checkbox :id :c-cat   :text "Category")
                      (sc/checkbox :id :c-comm  :text "Community")
                      (sc/button :id :search-btn :text "Search" :enabled? false)
                      (sc/button :id :add-ch-btn :text "Save as keyword ch" :enabled? false)
                      (sc/text :id :cond-text
                               :multi-line? true
                               :wrap-lines? true
                               :rows 3
                               :editable? true)]

      (sb/bind
       (sb/funnel
        c-title c-desc c-owner c-cat c-comm
        (sb/bind cond-text (sb/transform
                            (fn [text]
                              (when-let [c (db/where-clause text)]
                                text)))))
       (sb/b-send
        search-spec
        (fn [old-spec vals]
          (let [[title desc owner cat comm cond-text] vals]
            (when (and (or title desc owner cat comm) cond-text)
              {:target (->> [(when title :title) (when desc :description) (when owner :owner_name)
                             (when cat :category) (when comm :comm_name)]
                            (remove nil?)
                            set)
               :query cond-text})))))

      (sb/bind
       search-spec
       (sb/property search-btn :enabled?))

      (sc/border-panel
       :user-data search-spec ;; ここでエージェントへの参照を保持することで上からuser-dataでアクセスできる
       :north (sm/mig-panel
               :constraints ["fill, wrap 2" "[:400:400][]"]
               :items [[(sm/mig-panel
                         :border "Search target"
                         :constraints ["wrap 3, ins 10 15 10 15"]
                         :items [[c-title][c-desc][c-owner][c-cat][c-comm]])
                        "grow"]
                       [(sm/mig-panel
                         :border "Search condition"
                         :constraints ["fill, ins 10 10 10 10"]
                         :items [[(sc/scrollable cond-text) "grow"]])
                        "grow"]
                       [(sm/mig-panel
                         :constraints ["wrap 2, fill, ins 0 0 5 0"]
                         :items [[search-btn "align right"][add-ch-btn]
                                 [(sc/label :id :l-search-status :text " ") "span 2, al 50%"]])
                        "span 2, grow"]])
       :center (sc/scrollable (doto (SearchResultPanel. 2 2)
                                (sc/config! :id :sresult-panel)))
       ))))
