;; -*- coding: utf-8-unix -*-
(ns nico.ui.prefs-browsers
  (:require [clojure.tools.logging :as log]
            [slide.core :as slc]
            [seesaw.bind :as sb]
            [seesaw.core :as sc]
            [seesaw.mig :as sm]
            [seesaw.table :as st])
  (:import [javax.swing ListSelectionModel]
           [javax.swing.table DefaultTableCellRenderer]))

(defn- input-dialog [data]
  (let [new-data (agent data)
        name (sc/text :text (:name data))
        path (sc/text :text (if (= :default (:path data)) "" (:path data)))
        dlg (slc/dialog
             :title "Edit browser"
             :content (sm/mig-panel
                       :constraints ["wrap 2, ins 10 10 10 10" "[:50:][:400:]"]
                       :items [["Name:"][name "grow"]
                               ["Path:"][path "grow"]])
             :success-fn (fn [_] @new-data)
             :option-type :ok-cancel
             :id-ok :btn-ok)]
    (sb/bind name (sb/b-send new-data #(assoc %1 :name %2)))
    (sb/bind path (sb/b-send new-data #(assoc %1 :path %2)))
    (let [{:keys [btn-ok]} (sc/group-by-id dlg)]
      (sb/bind new-data
               (sb/transform #(and (:name %) (:path %) (not= data %)))
               (sb/property btn-ok :enabled?)))
    (sc/pack! dlg)))

(defn- string-renderer []
  (proxy [DefaultTableCellRenderer] []
    (getTableCellRendererComponent
     [tbl val isSelected hasFocus row col]
     (let [c (proxy-super getTableCellRendererComponent tbl val isSelected hasFocus row col)]
       (condp = col
         0 (when (= :default val) (.setText c "Default browser"))
         1 (when (= :default val) (.setText c "-")))
       c))))

(defn panel
  "curr-prefs: A current browsers preferences (map)
   update-fn: An updating function"
  [curr-prefs update-fn]
  (let [btn-add  (sc/button :text "Add")
        btn-edit (sc/button :text "Edit" :enabled? false)
        btn-del  (sc/button :text "Delete" :enabled? false)
        btn-up   (sc/button :text "Up" :enabled? false)
        btn-down (sc/button :text "Down" :enabled? false)
        ;; このテーブルはモデルの順序をそのまま表示する（ソーターを入れない）
        tbl-browsers (sc/table :model [:columns [{:key :name :text "Name" :class String}
                                                 {:key :path :text "Path" :class String}]
                                       :rows curr-prefs])]
    (let [cm (.getColumnModel tbl-browsers)
          sr (string-renderer)]
      (doto (.getColumn cm 0) (.setPreferredWidth 140) (.setCellRenderer sr))
      (doto (.getColumn cm 1) (.setPreferredWidth 260) (.setCellRenderer sr)))

    ;; set selection listener
    (.. tbl-browsers getSelectionModel (setSelectionMode ListSelectionModel/SINGLE_SELECTION))
    (sc/listen tbl-browsers :selection
               (fn [_]
                 (let [mdl (.getModel tbl-browsers)
                       row (.getSelectedRow tbl-browsers) ; SINGLE_SELECTION前提
                       nrow (st/row-count tbl-browsers)
                       vals (st/value-at tbl-browsers row)]
                   (sc/invoke-later
                    (sc/config! btn-edit :enabled? (and (<= 0 row (dec nrow)) (not= :default (:name vals))))
                    (sc/config! btn-del :enabled? (and (<= 0 row (dec nrow)) (not= :default (:name vals))))
                    (sc/config! btn-up :enabled?  (< 0 row nrow))
                    (sc/config! btn-down :enabled? (< 0 (inc row) nrow))))))

    ;; set action listeners
    (letfn [(new-prefs [tbl]
              (vec (map #(vector (:name %) (:path %)) (st/value-at tbl (range (st/row-count tbl))))))
            (move-row [tbl from-row to-row]
              (.. tbl getModel (moveRow from-row from-row to-row))
              (.. tbl getSelectionModel (setSelectionInterval to-row to-row)))]
      (sc/listen btn-up :action
                 (fn [_]
                   (let [from-row (.getSelectedRow tbl-browsers) ; SINGLE_SELECTION前提
                         to-row (dec from-row)]
                     (when (< 0 from-row (st/row-count tbl-browsers))
                       (sc/invoke-now (move-row tbl-browsers from-row to-row))
                       (update-fn (new-prefs tbl-browsers))))))
      (sc/listen btn-down :action
                 (fn [_]
                   (let [from-row (.getSelectedRow tbl-browsers) ; SINGLE_SELECTION前提
                         to-row (inc from-row)]
                     (when (< 0 to-row (st/row-count tbl-browsers))
                       (sc/invoke-now (move-row tbl-browsers from-row to-row))
                       (update-fn (new-prefs tbl-browsers))))))
      (sc/listen btn-del :action
                 (fn [_]
                   (let [row (.getSelectedRow tbl-browsers) ; SINGLE_SELECTION前提
                         vals (st/value-at tbl-browsers row)]
                     (when (and (<= 0 row (dec (st/row-count tbl-browsers)))
                                (not= :default (:name vals)))
                       (sc/invoke-now (st/remove-at! tbl-browsers row))
                       (update-fn (new-prefs tbl-browsers))))))
      (sc/listen btn-add :action
                 (fn [_]
                   (let [r (.getSelectedRow tbl-browsers)
                         row (if (neg? r) 0 r)]
                     (sc/invoke-now
                      (st/insert-at! tbl-browsers row {:name "(Edit it!)" :path :default})
                      (.. tbl-browsers getSelectionModel (setSelectionInterval row row)))
                     (update-fn (new-prefs tbl-browsers)))))
      (sc/listen btn-edit :action
                 (fn [_]
                   (let [row (.getSelectedRow tbl-browsers)
                         vals (st/value-at tbl-browsers row)]
                     (when (and (<= 0 row (dec (st/row-count tbl-browsers)))
                                (not= :default (:name vals)))
                       (when-let [new-vals (-> (input-dialog vals)
                                               (slc/move-to-center! (sc/to-root tbl-browsers))
                                               sc/show!)]
                         (sc/invoke-now (st/update-at! tbl-browsers row new-vals))
                         (update-fn (new-prefs tbl-browsers)))))))
      )

    (sm/mig-panel
     :constraints ["wrap 2, ins 10 10 10 10" "[:80:][:370:]"]
     :items [[btn-add][(sc/scrollable tbl-browsers) "spany 5, height 150"]
             [btn-edit]
             [btn-del]
             [btn-up]
             [btn-down]])))
