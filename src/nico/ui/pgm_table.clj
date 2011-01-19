;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "番組情報表示テーブル。SwingXのJXTableを使用している。"}
  nico.ui.pgm-table
  (:use [clojure.contrib.swing-utils :only [add-action-listener]]
	[clojure.contrib.seq-utils :only [indexed]])
  (:require [nico.prefs :as p]
	    [time-utils :as tu]
	    [nico.pgm :as pgm])
  (:import (java.awt Cursor Desktop Font)
	   (java.awt.event MouseListener MouseMotionListener)
	   (java.net URI)
	   (javax.swing BorderFactory JMenuItem JPopupMenu JTable ListSelectionModel
	                SwingConstants SwingUtilities)
	   (javax.swing.border EtchedBorder)
	   (javax.swing.table AbstractTableModel DefaultTableModel
			      DefaultTableColumnModel TableColumn)
	   (org.jdesktop.swingx JXTable)
	   (org.jdesktop.swingx.decorator HighlighterFactory FontHighlighter HighlightPredicate)
	   (org.jdesktop.swingx.renderer DefaultTableRenderer StringValue StringValues)))

(def *pgm-columns*
     (list
      {:key :member_only, :colName "限", :width 5,
       :renderer (DefaultTableRenderer.
		   (proxy [StringValue][] (getString [val] (if val "限" "")))
		   SwingConstants/CENTER)}
      {:key :title, :colName "タイトル", :width 300,
       :renderer (DefaultTableRenderer. StringValues/TO_STRING)}
      {:key :comm_name, :colName "コミュ名", :width 300,
       :renderer (DefaultTableRenderer. StringValues/TO_STRING)}
      {:key :pubdate, :colName "開始", :width 50,
       :renderer (DefaultTableRenderer.
		   (proxy [StringValue][]
		     (getString [val] (tu/format-time-short val)))
		   SwingConstants/CENTER)}
      {:key :owner_name, :colName "放送主", :width 60,
       :renderer (DefaultTableRenderer. StringValues/TO_STRING)}))
      ;; {:key :view, :colName "来場者数", :width 30,
      ;;  :renderer (DefaultTableRenderer. StringValues/TO_STRING SwingConstants/CENTER)}
      ;; {:key :num_res, :colName "コメ数", :width 30,
      ;;  :renderer (DefaultTableRenderer. StringValues/TO_STRING SwingConstants/CENTER)}))

(defn- pgm-colnum
  "*pgm-columns*の中から、指定されたキーのカラム番号を得る"
  [k]
  (some #(if (= (:key (fnext %)) k) (first %)) (indexed *pgm-columns*)))

(defn- pgm-column-model
  "番組情報テーブルのカラムモデルを生成する"
  []
  (letfn [(gen-col [i pc]
		   (doto (TableColumn. i (:width pc))
		     (.setHeaderValue (:colName pc))
		     (.setCellRenderer (:renderer pc))))]
    (let [col-model (DefaultTableColumnModel.)]
      (doseq [[i pc] (indexed *pgm-columns*)] (.addColumn col-model (gen-col i pc)))
      col-model)))

;; ProgramsTableModelは、pgmsを開始時刻でソートしたものを表示するTableModel。
;; 拡張メソッドgetUrlにより、番組URLを返す。
(gen-class
 :name nico.ui.ProgramsTableModel
 :extends javax.swing.table.AbstractTableModel
 :prefix "ptm-"
 :constructors {[clojure.lang.IPersistentMap] []}
 :state state
 :init init
 :methods [[isNew [int] boolean]
	   [getUrl [int] String]
	   [getProgramId [int] String]
	   [getProgramTitle [int] String]
	   [updateData [clojure.lang.IPersistentMap] void]
	   [getPgm [int] nico.pgm.Pgm]])

;; ProgramsTableは、ProgramsTableModelを表示するJXTable。
;; ツールチップを表示できる。
(gen-class
 :name nico.ui.ProgramsTable
 :extends org.jdesktop.swingx.JXTable
 :prefix "pt-"
 :constructors {[nico.ui.ProgramsTableModel javax.swing.table.TableColumnModel]
		[javax.swing.table.TableModel javax.swing.table.TableColumnModel]}
 :state state
 :init init)

(defn- open-url [cmd url]
  (if (= :default cmd)
    (.browse (Desktop/getDesktop) (URI. url))
    (.start (ProcessBuilder. [cmd url]))))

(defn pgm-table
  "番組情報テーブルを生成する"
  []
  (let [tbl (nico.ui.ProgramsTable. (nico.ui.ProgramsTableModel. {}) (pgm-column-model))]
    (doto tbl
      (.setSelectionMode ListSelectionModel/SINGLE_SELECTION)
      (.addHighlighter	;; 新着はボールドで表示する
       (FontHighlighter.
	(proxy [HighlightPredicate] []
	  (isHighlighted
	   [renderer adapter]
	   (.isNew (.getModel tbl) (.row adapter))))
	(Font. Font/DIALOG Font/BOLD 12)))
      (.addMouseListener
       (proxy [MouseListener] []
	 (mouseClicked
	  [e]
	  (let [c (.columnAtPoint tbl (.getPoint e)), r (.rowAtPoint tbl (.getPoint e))
		mc (.convertColumnIndexToModel tbl c), mr (.convertRowIndexToModel tbl r)]
	    (cond (and (= 2 (.getClickCount e)) (<= 0 mc) (<= 0 mr))
		  (let [[name cmd] (first (:browsers @(p/get-pref)))]
		    (open-url cmd (.getUrl (.getModel tbl) mr)))
		  (and (SwingUtilities/isRightMouseButton e) (<= 0 mr))
		  (let [pmenu (JPopupMenu.)
			pid (.getProgramId (.getModel tbl) mr)
			ptitle (.getProgramTitle (.getModel tbl) mr)
			url (.getUrl (.getModel tbl) mr)
			titem (JMenuItem. (format "%s (%s)" ptitle pid))]
		    (doto titem
		      (.setEnabled false))
		    (doto pmenu
		      (.add titem)
		      (.addSeparator))
		    (doseq [[name cmd] (:browsers @(p/get-pref))]
		      (let [mitem (if (= :default cmd)
				    (JMenuItem. "デフォルトブラウザで開く")
				    (JMenuItem. (str name "で開く")))]
			(add-action-listener mitem (fn [e] (open-url cmd url)))
			(doto pmenu (.add mitem))))
		    (.show pmenu tbl (.getX e) (.getY e))))))
	 (mouseEntered [e])
	 (mouseExited [e])
	 (mousePressed [e])
	 (mouseReleased [e])))
      (.addHighlighter	;; 偶数行奇数行で色を変える
       (HighlighterFactory/createSimpleStriping)))))

(defn- ptm-init [pgms]
  [[] (atom (sort-by #(:pubdate (val %)) #(compare %2 %1) pgms))])

(defn- ptm-updateData [this pgms]
  (reset! (.state this) (sort-by #(:pubdate (val %)) #(compare %2 %1) pgms))
  (.fireTableDataChanged this))

(defn- ptm-getUrl [this row]
  (:link (fnext (nth (seq @(.state this)) row))))

(defn- ptm-getProgramId [this row]
  (:id (fnext (nth (seq @(.state this)) row))))

(defn- ptm-getProgramTitle [this row]
  (:title (fnext (nth (seq @(.state this)) row))))

(defn- ptm-getColumnCount [this]
  (count *pgm-columns*))

(defn- ptm-getColumnName [this col]
  (:colName (nth *pgm-columns* col)))

(defn- ptm-getRowCount [this]
  (count @(.state this)))

(defn- ptm-isNew [this row]
  (pgm/new? (first (nth (seq @(.state this)) row))))

(defn- ptm-getValueAt [this row col]
  ((:key (nth *pgm-columns* col)) (fnext (nth (seq @(.state this)) row))))

(defn- ptm-getPgm [this row]
  (fnext (nth (seq @(.state this)) row)))

(defn- ptm-isCellEditable [this row col] false)

(defn pgm-table-model
  "ProgramsTableModelを生成する。"
  [pgms] (nico.ui.ProgramsTableModel. pgms))

(defn- pt-init [ptm pcm]
  [[ptm pcm] nil])

(defn- pt-getToolTipText [this e]
  (let [c (.columnAtPoint this (.getPoint e)), r (.rowAtPoint this (.getPoint e))
	mc (.convertColumnIndexToModel this c), mr (.convertRowIndexToModel this r)]
    (if (and (>= mc 0) (>= mr 0))
      (let [pgm (.getPgm (.getModel this) mr)]
	(str (format "<html>番組タイトル: %s<br>" (:title pgm))
	     (format "%s: %s<br>"
		     (if (= "channel" (:type pgm)) "チャンネル" "コミュ名") (:comm_name pgm))
	     (format "放送主: %s<br>" (:owner_name pgm))
	     (format "%s<br>" (:desc pgm))
	     (format "カテゴリ: %s<br>" (:category pgm))
	     (format "（%d分前に開始）" (tu/minute (tu/interval (:pubdate pgm) (tu/now)))))))))

