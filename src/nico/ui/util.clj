;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "UI Utilities."}
  nico.ui.util
  (:import (java.awt Dimension Font)
	   (javax.swing JButton JTextArea SpringLayout)))

(def *font* (Font. "Default" Font/PLAIN 12))
(def *btn-height* 25)
(def *btn-size* (Dimension. 100 *btn-height*))

(defn btn [text]
  (let [b (JButton.)]
    (doto b
      (.setText text)
      (.setMaximumSize *btn-size*)
      (.setMinimumSize *btn-size*)
      (.setPreferredSize *btn-size*))))

(defn do-add-expand
  "親コンポーネントに子を追加する。その際、指定されたパディングを残して一杯にひろげる。"
  [parent child pad]
  (let [layout (SpringLayout.)]
    (doto layout
      (.putConstraint SpringLayout/NORTH child pad SpringLayout/NORTH parent)
      (.putConstraint SpringLayout/SOUTH child (* -1 pad) SpringLayout/SOUTH parent)
      (.putConstraint SpringLayout/WEST child pad SpringLayout/WEST parent)
      (.putConstraint SpringLayout/EAST child (* -1 pad) SpringLayout/EAST parent))
    (doto parent
      (.setLayout layout)
      (.add child))))

(defn mlabel
  "複数行折り返し可能なラベル"
  ([text]
     (let [l (JTextArea. text)]
       (doto l
	 (.setFont *font*)
	 (.setOpaque false) (.setEditable false) (.setFocusable false) (.setLineWrap true))))
  ([col text]
     (let [ml (mlabel text)]
       (doto ml
	 (.setColumns col)))))

