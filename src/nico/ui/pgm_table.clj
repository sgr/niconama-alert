;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "番組情報表示テーブル。"}
  nico.ui.pgm-table
  (:use [clojure.tools.swing-utils :only [add-action-listener]]
        [nico.ui.util])
  (:require [clojure.string :as s]
	    [nico.prefs :as p]
	    [str-utils :as su]
	    [time-utils :as tu])
  (:import [java.awt Color Font]
           [java.awt.font TextAttribute]
	   [javax.swing JLabel JMenuItem JPopupMenu JTable ListSelectionModel SwingUtilities]
	   [javax.swing.table AbstractTableModel DefaultTableColumnModel TableColumn]))

(def ^{:private true} DESC-COL 64)

;; PgmCellRendererは、番組表のセルレンダラー。
;; 新着の番組をボールド、コミュ限の番組を青字で表示する。
(gen-class
 :name nico.ui.PgmCellRenderer
 :extends nico.ui.StripeRenderer
 :exposes-methods {getTableCellRendererComponent gtcrc}
 :prefix "pr-"
 :constructors {[] []}
 :state state
 :init init
 :post-init post-init)

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
	   [isMemberOnly [int] boolean]
	   [getUrl [int] String]
	   [getProgramId [int] clojure.lang.Keyword]
	   [getProgramTitle [int] String]
	   [setPgms [clojure.lang.IPersistentMap] void]
	   [getPgm [int] nico.pgm.Pgm]])

(gen-class
 :name nico.ui.PgmMouseListener
 :extends java.awt.event.MouseAdapter
 :prefix "pml-"
 :constructors {[javax.swing.JTable] []}
 :state state
 :init init)

;; ProgramsTableは、ProgramsTableModelを表示するJTable。
;; ツールチップを表示できる。
(gen-class
 :name nico.ui.ProgramsTable
 :extends javax.swing.JTable
 :prefix "pt-"
 :constructors {[nico.ui.ProgramsTableModel javax.swing.table.TableColumnModel]
		[javax.swing.table.TableModel javax.swing.table.TableColumnModel]}
 :state state
 :init init
 :methods [[setSortable [boolean] void]])

(defn- pr-init [] [[] (atom {})])
(defn- pr-post-init [this]
  (let [attrs (doto (.getAttributes (.getFont this))
                (.put TextAttribute/WEIGHT TextAttribute/WEIGHT_BOLD))]
    (swap! (.state this) assoc :bold-font (Font. attrs))))
(defn- pr-getTableCellRendererComponent [this tbl val selected focus row col]
  (.gtcrc this tbl val selected focus row col)
  (when-let [pmdl (let [mdl (.getModel tbl)]
		    (if (= nico.ui.ProgramsTableModel (class mdl)) mdl nil))]
    (let [mr (.convertRowIndexToModel tbl row) pgm (.getPgm pmdl mr)]
      (when (tu/within? (:fetched_at pgm) (tu/now) 60)
        (.setFont this (:bold-font @(.state this))))
      (when (:member_only pgm) (.setForeground this Color/BLUE))))
  this)

(def ^{:private true} PGM-COLUMNS
  (list
   {:key :title, :colName "タイトル", :width 300, :renderer (nico.ui.PgmCellRenderer.)}
   {:key :comm_name, :colName "コミュ名", :width 300, :renderer (nico.ui.PgmCellRenderer.)}
   {:key :pubdate, :colName "開始", :width 50,
    :renderer (doto (nico.ui.PgmCellRenderer.) (.setHorizontalAlignment JLabel/CENTER))}
   {:key :owner_name, :colName "放送主", :width 60, :renderer (nico.ui.PgmCellRenderer.)}))

(defn- pgm-colnum
  "PGM-COLUMNS の中から、指定されたキーのカラム番号を得る"
  [k]
  (some #(if (= (:key (fnext %)) k) (first %)) (map-indexed vector PGM-COLUMNS)))

(defn- pgm-column-model
  "番組情報テーブルのカラムモデルを生成する"
  []
  (letfn [(gen-col [i pc]
		   (doto (TableColumn. i (:width pc))
		     (.setHeaderValue (:colName pc))
		     (.setCellRenderer (:renderer pc))))]
    (let [col-model (DefaultTableColumnModel.)]
      (doseq [[i pc] (map-indexed vector PGM-COLUMNS)] (.addColumn col-model (gen-col i pc)))
      col-model)))

(defn- ptm-init [pgms]
  [[] (atom (sort-by #(:pubdate (val %)) #(compare %2 %1) pgms))])

(defn- ptm-setPgms [this pgms]
  (reset! (.state this) (sort-by #(:pubdate (val %)) #(compare %2 %1) pgms))
  (.fireTableDataChanged this))

(defn- ptm-getUrl [this row]
  (:link (fnext (nth (seq @(.state this)) row))))

(defn- ptm-getProgramId [this row]
  (:id (fnext (nth (seq @(.state this)) row))))

(defn- ptm-getProgramTitle [this row]
  (:title (fnext (nth (seq @(.state this)) row))))

(defn- ptm-getColumnCount [this]
  (count PGM-COLUMNS))

(defn- ptm-getColumnName [this col]
  (:colName (nth PGM-COLUMNS col)))

(defn- ptm-getRowCount [this]
  (count @(.state this)))

(defn- ptm-isNew [this row]
  (tu/within? (:fetched_at (fnext (nth (seq @(.state this)) row))) (tu/now) 60))

(defn- ptm-isMemberOnly [this row]
  (:member_only (fnext (nth (seq @(.state this)) row))))

(defn- ptm-getValueAt [this row col]
  ((:key (nth PGM-COLUMNS col)) (fnext (nth (seq @(.state this)) row))))

(defn- ptm-getPgm [this row]
  (fnext (nth (seq @(.state this)) row)))

(defn- ptm-isCellEditable [this row col] false)

(defn- pt-init [ptm pcm]
  [[ptm pcm] nil])

(defn- pt-getToolTipText [this e]
  (let [c (.columnAtPoint this (.getPoint e)), r (.rowAtPoint this (.getPoint e))]
    (when (and (<= 0 c) (<= 0 r))
      (let [mc (.convertColumnIndexToModel this c), mr (.convertRowIndexToModel this r)]
	(if (and (<= 0 mc) (<= 0 mr))
	  (let [pgm (.getPgm (.getModel this) mr)]
	    (str (format "<html>番組タイトル: %s<br>" (su/ifstr (:title pgm) ""))
		 (format "%s: %s<br>"
			 (if (= "channel" (:type pgm)) "チャンネル" "コミュ名")
			 (su/ifstr (:comm_name pgm) ""))
		 (format "放送主: %s<br>" (su/ifstr (:owner_name pgm) ""))
		 (format "%s<br>" (s/join "<br>" (su/split-by-length
						  (su/ifstr (:desc pgm) "") DESC-COL)))
		 (format "カテゴリ: %s<br>" (su/ifstr (:category pgm) ""))
		 (format "（%d分前に開始）" (tu/minute (tu/interval (:pubdate pgm) (tu/now)))))))))))

(defn- pt-setSortable [this sortability] (.setAutoCreateRowSorter this sortability))

(defn- pml-init [tbl] [[] (atom tbl)])
(defn- pml-mouseClicked [this evt]
  (let [tbl @(.state this)
        c (.columnAtPoint tbl (.getPoint evt))
        r (.rowAtPoint tbl (.getPoint evt))]
    (when (and (<= 0 c) (<= 0 r))
      (let [mc (.convertColumnIndexToModel tbl c), mr (.convertRowIndexToModel tbl r)]
        (cond (and (= 2 (.getClickCount evt)) (<= 0 mc) (<= 0 mr))
              (p/open-url :first (.getUrl (.getModel tbl) mr))
              (and (SwingUtilities/isRightMouseButton evt) (<= 0 mr))
              (let [pmenu (JPopupMenu.)
                    pid (.getProgramId (.getModel tbl) mr)
                    ptitle (.getProgramTitle (.getModel tbl) mr)
                    url (.getUrl (.getModel tbl) mr)
                    titem (JMenuItem. (format "%s (%s)" ptitle (name pid)))]
                (doto titem
                  (.setEnabled false))
                (doto pmenu
                  (.add titem)
                  (.addSeparator))
                (doseq [[name ofn] (p/browsers)]
                  (let [mitem (if (= :default name)
                                (JMenuItem. "デフォルトブラウザで開く")
                                (JMenuItem. (str name "で開く")))]
                    (add-action-listener mitem (fn [e] (ofn url)))
                    (doto pmenu (.add mitem))))
                (.show pmenu tbl (.getX evt) (.getY evt))))))))

(defn pgm-table-model
  "ProgramsTableModelを生成する。"
  [pgms]
  (nico.ui.ProgramsTableModel. pgms))

(defn pgm-table
  "番組情報テーブルを生成する"
  []
  (let [tbl (nico.ui.ProgramsTable. (nico.ui.ProgramsTableModel. {}) (pgm-column-model))]
    (doto tbl
      (.setSelectionMode ListSelectionModel/SINGLE_SELECTION)
      (.addMouseListener (nico.ui.PgmMouseListener. tbl)))))
