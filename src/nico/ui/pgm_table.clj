;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "番組情報表示テーブル。"}
  nico.ui.pgm-table
  (:use [clojure.set :only [difference]]
        [clojure.tools.swing-utils :only [add-action-listener]]
        [nico.thumbnail :only [fetch]]
        [nico.ui.util])
  (:require [clojure.string :as s]
            [nico.prefs :as p]
            [str-utils :as su]
            [time-utils :as tu])
  (:import [java.awt Color Font]
           [java.awt.font TextAttribute]
           [java.awt.event MouseEvent]
           [javax.swing JLabel JMenuItem JPopupMenu JTable ListSelectionModel SwingUtilities]
           [javax.swing.table AbstractTableModel DefaultTableColumnModel TableColumn]))

(def ^{:private true} DESC-COL 64)
(def ^{:private true} THUMBNAIL-WIDTH 32)
(def ^{:private true} THUMBNAIL-HEIGHT 32)

;; PgmCellRendererは、番組表のセルレンダラー。
;; 新着の番組をボールド、コミュ限の番組を青字で表示する。
(gen-class
 :name nico.ui.PgmCellRenderer
 :extends nico.ui.StripeRenderer
 :exposes-methods {getTableCellRendererComponent gtcrc}
 :prefix "pr-")

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
           [getIds [] clojure.lang.IPersistentSet]
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
 :post-init post-init
 :methods [[setSortable [boolean] void]])

(let [attrs (doto (.getAttributes DEFAULT-FONT)
              (.put TextAttribute/WEIGHT TextAttribute/WEIGHT_BOLD))
      BOLD-FONT (.deriveFont DEFAULT-FONT attrs)]
  (defn- pr-getTableCellRendererComponent [^nico.ui.PgmCellRenderer this ^JTable tbl val selected focus row col]
    (.gtcrc this tbl val selected focus row col)
    (let [mr (.convertRowIndexToModel tbl row)
          pgm (.getPgm ^nico.ui.ProgramsTableModel (.getModel tbl) mr)]
      (if (tu/within? (:fetched_at pgm) (tu/now) 60)
        (.setFont this BOLD-FONT)
        (.setFont this DEFAULT-FONT))
      (when (:member_only pgm) (.setForeground this Color/BLUE)))
    this))

(let [text-renderer (nico.ui.PgmCellRenderer.)]
  (def ^{:private true} PGM-COLUMNS
    (list
     {:key :thumbnail, :colName "", :class javax.swing.ImageIcon, :width THUMBNAIL-WIDTH, :renderer (nico.ui.StripeImageCellRenderer.)}
     {:key :title, :colName "タイトル", :class String :width 300, :renderer text-renderer}
     {:key :comm_name, :colName "コミュ名", :class String :width 250, :renderer text-renderer}
     {:key :pubdate, :colName "開始", :class java.util.Date :width 80, :renderer text-renderer}
     {:key :owner_name, :colName "放送主", :class String :width 80, :renderer text-renderer})))

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

(letfn [(sort-pgms [pgms] (sort-by :pubdate #(compare %2 %1) pgms))]
  (defn- ptm-init [pgms]
    [[] (atom (sort-pgms pgms))])

  (defn- ptm-setPgms [^nico.ui.ProgramsTableModel this pgms]
    (let [old-pgms (reduce (fn [m pgm] (assoc m (:id pgm) pgm)) {} @(.state this))
          oks (apply hash-set (keys old-pgms))
          nks (apply hash-set (keys pgms))
          del-ids (difference oks nks)
          add-ids (difference nks oks)
          add-pgms (map #(when-let [pgm (get pgms %)]
                           (assoc pgm :thumbnail (fetch (:thumbnail pgm) THUMBNAIL-WIDTH THUMBNAIL-HEIGHT)))
                        add-ids)]
      (reset! (.state this) (sort-pgms (concat (vals (apply dissoc old-pgms del-ids)) add-pgms)))
      (.fireTableDataChanged this))))

(defn- ptm-getIds [^nico.ui.ProgramsTableModel this]
  (apply hash-set (map :id @(.state this))))

(defn- ptm-getColumnCount [^nico.ui.ProgramsTableModel this]
  (count PGM-COLUMNS))

(defn- ptm-getColumnClass [^nico.ui.ProgramsTableModel this col]
  (:class (nth PGM-COLUMNS col)))

(defn- ptm-getColumnName [^nico.ui.ProgramsTableModel this col]
  (:colName (nth PGM-COLUMNS col)))

(defn- ptm-getRowCount [^nico.ui.ProgramsTableModel this]
  (count @(.state this)))

(defn- ptm-isCellEditable [^nico.ui.ProgramsTableModel this row col] false)

(letfn [(get-pgm [this row] (nth @(.state this) row))]
  (defn- ptm-getUrl [^nico.ui.ProgramsTableModel this row]
    (:link (get-pgm this row)))

  (defn- ptm-getProgramId [^nico.ui.ProgramsTableModel this row]
    (:id (get-pgm this row)))

  (defn- ptm-getProgramTitle [^nico.ui.ProgramsTableModel this row]
    (:title (get-pgm this row)))

  (defn- ptm-isNew [^nico.ui.ProgramsTableModel this row]
    (tu/within? (:fetched_at (get-pgm this row)) (tu/now) 60))

  (defn- ptm-isMemberOnly [^nico.ui.ProgramsTableModel this row]
    (:member_only (get-pgm this row)))

  (defn- ptm-getValueAt [^nico.ui.ProgramsTableModel this row col]
    (let [k (:key (nth PGM-COLUMNS col))]
      (get (get-pgm this row) k)))

  (defn- ptm-getPgm [^nico.ui.ProgramsTableModel this row]
    (get-pgm this row)))

(defn- pt-init [ptm pcm]
  [[ptm pcm] nil])

(defn- pt-post-init [^nico.ui.ProgramsTable this ptm pcm]
  (.setRowHeight this (+ THUMBNAIL-HEIGHT 4)))

(defn- pt-getToolTipText [^nico.ui.ProgramsTable this ^MouseEvent e]
  (let [c (.columnAtPoint this (.getPoint e)), r (.rowAtPoint this (.getPoint e))]
    (when (and (<= 0 c) (<= 0 r))
      (let [mc (.convertColumnIndexToModel this c), mr (.convertRowIndexToModel this r)]
        (if (and (<= 0 mc) (<= 0 mr))
          (let [pgm (.getPgm ^nico.ui.ProgramsTableModel (.getModel this) mr)]
            (str "<html>"
                 (format "%s<br>" (s/join "<br>" (su/split-by-length
                                                  (su/ifstr (:desc pgm) "") DESC-COL)))
                 (format "カテゴリ: %s<br>" (su/ifstr (:category pgm) ""))
                 (format "（%d分前に開始）" (tu/minute (tu/interval (:pubdate pgm) (tu/now))))
                 "</html>")))))))

(defn- pt-setSortable [^nico.ui.ProgramsTable this sortability]
  (.setAutoCreateRowSorter this sortability))

(defn- pml-init [tbl] [[] (atom tbl)])
(defn- pml-mouseClicked [^nico.ui.PgmMouseListener this ^MouseEvent evt]
  (let [^nico.ui.ProgramsTable tbl @(.state this)
        c (.columnAtPoint tbl (.getPoint evt))
        r (.rowAtPoint tbl (.getPoint evt))]
    (when (and (<= 0 c) (<= 0 r))
      (let [mc (.convertColumnIndexToModel tbl c), mr (.convertRowIndexToModel tbl r)
            ^nico.ui.ProgramsTableModel mdl (.getModel tbl)]
        (cond (and (= 2 (.getClickCount evt)) (<= 0 mc) (<= 0 mr))
              (p/open-url :first (.getUrl mdl mr))
              (and (SwingUtilities/isRightMouseButton evt) (<= 0 mr))
              (let [pmenu (JPopupMenu.)
                    titem (JMenuItem. (format "%s (%s)"
                                              (.getProgramTitle mdl mr)
                                              (name (.getProgramId mdl mr))))]
                (doto titem
                  (.setEnabled false))
                (doto pmenu
                  (.add titem)
                  (.addSeparator))
                (doseq [[name ofn] (p/browsers)]
                  (let [mitem (if (= :default name)
                                (JMenuItem. "デフォルトブラウザで開く")
                                (JMenuItem. (str name "で開く")))]
                    (add-action-listener mitem (fn [e] (ofn (.getUrl mdl mr))))
                    (doto pmenu (.add mitem))))
                (.show pmenu tbl (.getX evt) (.getY evt))))))))

(defn ^nico.ui.ProgramsTableModel pgm-table-model
  "ProgramsTableModelを生成する。"
  [pgms]
  (nico.ui.ProgramsTableModel. pgms))

(defn ^nico.ui.ProgramsTable pgm-table
  "番組情報テーブルを生成する"
  []
  (let [tbl (nico.ui.ProgramsTable. (nico.ui.ProgramsTableModel. {}) (pgm-column-model))]
    (doto tbl
      (.setSelectionMode ListSelectionModel/SINGLE_SELECTION)
      (.addMouseListener (nico.ui.PgmMouseListener. tbl)))))
