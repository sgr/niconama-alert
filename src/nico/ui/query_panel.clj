;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "query panel"}
  nico.ui.query-panel
  (:use [clojure.tools.logging])
  (:require [query-utils :as qu]
            [nico.ui.util :as uu])
  (:import [java.awt Color Dimension]
	   [javax.swing BorderFactory JPanel JScrollPane JTextArea JTextField]
	   [javax.swing.event DocumentListener]
	   [javax.swing.text PlainDocument]))

(def ^{:private true} QUERY-PANEL-SIZE (Dimension. 440 130))

(gen-class
 :name nico.ui.QueryPanel
 :extends javax.swing.JPanel
 :prefix "qp-"
 :constructors {[String] []}
 :state state
 :init init
 :post-init post-init
 :methods [[getQuery [] String]
           [setQuery [String] void]
           [addListener [clojure.lang.IFn] void]
           [isOK [] boolean]])

(defn- qp-init [query] [[] (atom {:ok false :rq nil :sq nil})])
(defn- qp-getQuery [this] (when-let [rq (:rq @(.state this))] (rq)))
(defn- qp-setQuery [this query] (when-let [sq (:sq @(.state this))] (sq query)))
(defn- qp-isOK [this] (:ok @(.state this)))
(defn- qp-addListener [this f] (when-let [al (:al @(.state this))] (al f)))

(defn- qp-post-init [this query]
  (let [query-area (JTextArea.), query-doc (PlainDocument.)
	query-border (.getBorder query-area)]
    (letfn [(set-border [b]
              (if b
                (.setBorder query-area query-border)
                (.setBorder query-area (BorderFactory/createLineBorder Color/RED))))
            (set-ok [b]
              (if b
                (swap! (.state this) assoc :ok true)
                (swap! (.state this) assoc :ok false)))
	    (check []
              (if (< 0 (.getLength query-doc))
                (try
                  (let [tq (qu/to-where-clause (read-query) [:title])]
                    (debug (format "query: %s" tq))
                    (set-border true)
                    (set-ok true))
                  (catch Exception e
                    (warn (.getMessage e))
                    (set-border false)
                    (set-ok false)))
                (do (set-border true)
                    (set-ok false))))
            (add-listener [f]
              (.addDocumentListener query-doc (proxy [DocumentListener] []
                                                (changedUpdate [_] (check) (f))
                                                (insertUpdate [_] (check) (f))
                                                (removeUpdate [_] (check) (f)))))
            (read-query [] (.getText query-doc 0 (.getLength query-doc)))
            (set-query [query] (.setText query-area query))]
      (swap! (.state this) assoc :al add-listener)
      (swap! (.state this) assoc :rq read-query)
      (swap! (.state this) assoc :sq set-query)
      (doto query-area
	(.setLineWrap true)
	(.setDocument query-doc))
      (set-query query)
      (check)
      (doto this
	(.setBorder (BorderFactory/createTitledBorder "検索条件"))
	(.setMinimumSize QUERY-PANEL-SIZE)
	(.setPreferredSize QUERY-PANEL-SIZE)
	(uu/do-add-expand (JScrollPane. query-area) 5)))))
