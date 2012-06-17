;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "target panel"}
  nico.ui.target-panel
  (:use [clojure.tools.swing-utils :only [add-action-listener]])
  (:import [java.awt Color FlowLayout]
	   [javax.swing BorderFactory JCheckBox JPanel]))

(gen-class
 :name nico.ui.TargetPanel
 :extends javax.swing.JPanel
 :prefix "tp-"
 :constructors {[clojure.lang.ISeq] []}
 :state state
 :init init
 :post-init post-init
 :methods [[getTargets [] clojure.lang.ISeq]
           [setTargets [clojure.lang.ISeq] void]
           [addListener [clojure.lang.IFn] void]
           [isOK [] boolean]])

(defn- tp-init [targets] [[] (atom {:ok false :gt nil :st nil :al nil})])
(defn- tp-getTargets [this] (when-let [gt (:gt @(.state this))] (gt)))
(defn- tp-setTargets [this targets] (when-let [st (:st @(.state this))] (st targets)))
(defn- tp-isOK [this] (:ok @(.state this)))
(defn- tp-addListener [this f] (when-let [al (:al @(.state this))] (al f)))

(defn- tp-post-init [this targets]
  (let [inner-panel (doto (JPanel.) (.setBorder (BorderFactory/createEmptyBorder 1 1 1 1)))
	target-border (.getBorder inner-panel)
	cb-title (JCheckBox. "タイトル"), cb-desc (JCheckBox. "説明"), cb-owner (JCheckBox. "放送主")
	cb-category (JCheckBox. "カテゴリ"), cb-comm-name (JCheckBox. "コミュ名")]
    (letfn [(check []
              (let [selected (or (.isSelected cb-title)
                                 (.isSelected cb-desc)
                                 (.isSelected cb-owner)
                                 (.isSelected cb-category)
                                 (.isSelected cb-comm-name))]
                (if (false? selected)
                  (do (swap! (.state this) assoc :ok false)
                      (.setBorder inner-panel (BorderFactory/createLineBorder Color/RED)))
                  (do (swap! (.state this) assoc :ok true)
                      (.setBorder inner-panel target-border)))))
            (add-listener [f]
              (doseq [c [cb-title cb-desc cb-owner cb-category cb-comm-name]]
                (doto c (add-action-listener (fn [_] (check) (f))))))
            (get-targets []
              (filter #(not (nil? %))
                      (list (when (.isSelected cb-title) :title)
                            (when (.isSelected cb-desc) :desc)
                            (when (.isSelected cb-owner) :owner_name)
                            (when (.isSelected cb-category) :category)
                            (when (.isSelected cb-comm-name) :comm_name))))
            (set-targets [targets]
              (when (seq? targets)
                (doseq [target targets] (condp = target
                                          :title      (.setSelected cb-title true)
                                          :desc       (.setSelected cb-desc true)
                                          :owner_name (.setSelected cb-owner true)
                                          :category   (.setSelected cb-category true)
                                          :comm_name  (.setSelected cb-comm-name true)))))]
      (swap! (.state this) assoc :al add-listener)
      (swap! (.state this) assoc :gt get-targets)
      (swap! (.state this) assoc :st set-targets)
      (set-targets targets)
      (doto inner-panel
	(.setLayout (FlowLayout.))
	(.add cb-title) (.add cb-desc) (.add cb-owner) (.add cb-category) (.add cb-comm-name))
      (check)
      (doto this
	(.setBorder (BorderFactory/createTitledBorder "検索対象"))
	(.add inner-panel)))))
