;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "番組情報表示テーブル。"}
  nico.ui.pgm-table
  (:use [clojure.set :only [difference]]
        [clojure.tools.swing-utils :only [do-swing add-action-listener]]
        [clojure.tools.logging]
        [nico.thumbnail :only [fetch]]
        [nico.ui.util])
  (:require [clojure.string :as s]
            [nico.prefs :as p]
            [str-utils :as su]
            [time-utils :as tu])
  (:import [java.awt Color Font]
           [java.awt.font TextAttribute]
           [java.awt.event MouseEvent]
           [javax.swing JLabel JMenuItem JPopupMenu JTable JTextArea ListSelectionModel SwingUtilities]
           [javax.swing.event TableModelEvent]
           [javax.swing.table AbstractTableModel DefaultTableColumnModel TableColumn TableRowSorter]
           [javax.swing.text JTextComponent View]))

(def ^{:private true} DESC-COL 64)

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
 :exposes-methods {columnMarginChanged superColumnMarginChanged
                   tableChanged superTableChanged}
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

(defn- ptm-init [pgms] [[] (java.util.Vector.)])
(defn- ptm-setPgms [^nico.ui.ProgramsTableModel this pgms]
  (letfn [(to-ranges [lst]
            (reduce (fn [rs itm]
                      (if-let [litm (-> rs last last)]
                        (if (= (dec litm) itm)
                          (conj (subvec rs 0 (dec (count rs)))
                                (conj (last rs) itm))
                          (conj rs [itm]))
                        [[itm]]))
                    [] lst))]
    (let [rev-idx (reduce (fn [m [idx pgm]] (assoc m (:id pgm) idx)) {} (map-indexed vector (.state this)))
          oks (apply hash-set (keys rev-idx))
          nks (apply hash-set (keys pgms))
          del-ids (difference oks nks)
          add-ids (difference nks oks)
          del-idxes (sort #(> %1 %2) (map #(get rev-idx %) del-ids)) ; 逆順にしないと削除時によろしくない
          del-idxes-ranges (to-ranges del-idxes)
          add-pgms (map #(when-let [pgm (get pgms %)]
                           (assoc pgm :thumbnail (fetch (:thumbnail pgm) THUMBNAIL-WIDTH THUMBNAIL-HEIGHT)))
                        add-ids)]
      (doseq [r del-idxes-ranges]
        (let [osize (.size (.state this))]
          (doseq [idx r] (.removeElementAt (.state this) idx))
          (trace (format "fireTableRowsDeleted(%d): %d - %d (%d -> %d)" (count r) (last r) (first r) osize (.size (.state this)))))
        (.fireTableRowsDeleted this (last r) (first r)))
      (when (< 0 (count add-pgms))
        (let [fidx (.size (.state this))]
          (doseq [pgm add-pgms] (when pgm (.add (.state this) pgm)))
          (trace (format "fireTableRowsInserted(%d): %d - %d" (count add-pgms) fidx (dec (.size (.state this)))))
          (.fireTableRowsInserted this fidx (dec (.size (.state this)))))))))

(defn- ptm-getIds [^nico.ui.ProgramsTableModel this]
  (apply hash-set (map :id (.state this))))

(defn- ptm-getRowCount [^nico.ui.ProgramsTableModel this]
  (.size (.state this)))

(defn- ptm-getColumnCount [^nico.ui.ProgramsTableModel this]
  (count PGM-COLUMNS))

(defn- ptm-getColumnClass [^nico.ui.ProgramsTableModel this col]
  (:class (nth PGM-COLUMNS col)))

(defn- ptm-getColumnName [^nico.ui.ProgramsTableModel this col]
  (:colName (nth PGM-COLUMNS col)))

(defn- ptm-isCellEditable [^nico.ui.ProgramsTableModel this row col] false)

(letfn [(get-pgm [this row] (.get (.state this) row))
        (get-pgm-old [this row] (nth @(.state this) row))]
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

(letfn [(ago [t n]
          (let [intvl (tu/interval t n)]
            (if (> 60000 intvl) (format "%d秒前" (int (/ intvl 1000))) (format "%d分前" (tu/minute intvl)))))]
  (defn- pt-getToolTipText [^nico.ui.ProgramsTable this ^MouseEvent e]
    (let [c (.columnAtPoint this (.getPoint e)), r (.rowAtPoint this (.getPoint e))]
      (when (and (<= 0 c) (<= 0 r))
        (let [mc (.convertColumnIndexToModel this c), mr (.convertRowIndexToModel this r)]
          (when (and (<= 0 mc) (<= 0 mr))
            (let [pgm (.getPgm ^nico.ui.ProgramsTableModel (.getModel this) mr)
                  now (tu/now)]
              (str "<html>"
                   (format "<table><tr><th>説明</th><td>%s</td></tr>"
                           (s/join "<br/>" (su/split-by-length (su/ifstr (:desc pgm) "") DESC-COL)))
                   (format "<tr><th>カテゴリ</th><td>%s</td></tr>" (su/ifstr (:category pgm) ""))
                   (format "<tr><th>取得</th><td>%s (%s)</td></tr>"
                           (ago (:fetched_at pgm) now) (tu/format-time-short (:fetched_at pgm)))
                   (format "<tr><th>更新</th><td>%s (%s)</td></tr>"
                           (ago (:updated_at pgm) now) (tu/format-time-short (:updated_at pgm)))
                   (format "<tr><th>開始</th><td>%s (%s)</td></tr></table>"
                           (ago (:pubdate pgm) now) (tu/format-time-short (:pubdate pgm)))
                   "</html>"))))))))

(defn- pt-setSortable [^nico.ui.ProgramsTable this sortability]
  (if sortability
    (.setRowSorter this (doto (TableRowSorter. (.getModel this))
                          (.setSortKeys (list (javax.swing.RowSorter$SortKey. 3 javax.swing.SortOrder/DESCENDING)))
                          (.setSortsOnUpdates true)
                          (.setSortable 0 false)))
    (.setRowSorter this nil)))

(letfn [(max-row-height [^nico.ui.ProgramsTable this row]
          (apply max (for [colidx (range 0 (dec (.getColumnCount this)))]
                       (let [vc (.convertColumnIndexToView this colidx)
                             col (.getColumn (.getColumnModel this) vc)]
                         (let [c (.prepareRenderer this (.getCellRenderer col) row vc)]
                           (if (instance? javax.swing.text.JTextComponent c)
                             (text-component-height c)
                             (-> c .getPreferredSize .height)))))))
        (update-row-height [^nico.ui.ProgramsTable tbl row]
          (let [oh (.getRowHeight tbl row)
                nh (max-row-height tbl row)]
            (when (not= oh nh)
              (trace (format "setRowHeight: %d -> %d" oh nh))
              (do-swing (.setRowHeight tbl row nh)))))]

  (defn- pt-post-init [^nico.ui.ProgramsTable this ptm pcm]
    (let [tbl this
          listener (proxy [java.beans.PropertyChangeListener][]
                     (propertyChange [^java.beans.PropertyChangeEvent evt]
                       (when (= "width" (.getPropertyName evt))
                         (doseq [row (range 0 (.getRowCount tbl))]
                           (update-row-height tbl row)))))]
      (doseq [column (enumeration-seq (.getColumns (.getColumnModel this)))]
        (.addPropertyChangeListener column listener)))
    (.setReorderingAllowed (.getTableHeader this) false)
    (.setRowHeight this (+ 8 THUMBNAIL-HEIGHT))
    (.setRowMargin this 4))

  (defn- pt-tableChanged-- [^nico.ui.ProgramsTable this ^javax.swing.event.TableModelEvent e]
    (.superTableChanged this e)
    (let [frow (.getFirstRow e) lrow (.getLastRow e) type (.getType e)]
      (trace (format "caught an TableModelEvent[%s]: %d - %d"
                     (condp = type TableModelEvent/INSERT "INSERT" TableModelEvent/UPDATE "UPDATE" TableModelEvent/DELETE "DELETE")
                     frow lrow))
      (when (and (or (= type TableModelEvent/INSERT) (= type TableModelEvent/UPDATE))
                 (every? #(<= 0 %) [frow lrow]))
        (cond
         (< frow lrow) (doseq [row (range frow (min lrow (.getRowCount this)))]
                         (update-row-height this (.convertRowIndexToView this row)))
         (= frow lrow) (update-row-height this (.convertRowIndexToView this frow))))))

  (defn- pt-columnMarginChanged [^nico.ui.ProgramsTable this ^javax.swing.event.ChangeEvent e]
    (.superColumnMarginChanged this e)
    (doseq [row (range 0 (.getRowCount this))]
      (update-row-height this row))))

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
