;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "タブに閉じるボタンと種別アイコンがついたJTabbedPane"}
  nico.ui.ext-tabbed-pane
  (:use [clojure.tools.swing-utils :only [do-swing do-swing-and-wait add-action-listener]])
  (:require [clojure.java.jdbc :as jdbc]
            [concurrent-utils :as c]
            [time-utils :as tu]
	    [nico.alert :as al]
            [nico.api :as api]
	    [nico.api-updator :as nau]
	    [nico.pgm :as pgm]
            [nico.rss-updator :as nr]
	    [nico.ui.key-val-dlg :as ukvd]
	    [nico.ui.kwd-tab-dlg :as uktd]
	    [nico.ui.pgm-panel]
	    [nico.ui.search-panel]
            [nico.ui.tab-component])
  (:import [java.awt.event MouseListener]
	   [javax.swing JCheckBoxMenuItem JMenuItem JOptionPane JPopupMenu SwingUtilities]
           [java.util.concurrent TimeUnit]))

(gen-class
 :name nico.ui.ExtTabbedPane
 :extends javax.swing.JTabbedPane
 :prefix "etp-"
 :constructors {[] []}
 :state state
 :init init
 :post-init post-init
 :exposes-methods {addTab addTabSuper}
 :methods [[addTab [clojure.lang.PersistentArrayMap] void]
           [addTabs [clojure.lang.IPersistentVector] void]
	   [getTabMenuItems [int] clojure.lang.IPersistentVector]
	   [getTabPrefs [] clojure.lang.IPersistentVector]])

(defn- etp-init []
  [[] (atom {:tab-prefs {}
             :tab-titles {}
             :conn (delay (pgm/get-ro-conn))
             :pstmts {}})])

(defn- confirm-rem-tab-fn [tpane tab]
  (fn [e]
    (when (= JOptionPane/OK_OPTION
	     (JOptionPane/showConfirmDialog
	      tpane "タブを削除しますか？" "削除確認"
	      JOptionPane/OK_CANCEL_OPTION JOptionPane/WARNING_MESSAGE))
      (.removeTabAt tpane (.indexOfTabComponent tpane tab))
      (let [tprefs (:tab-prefs @(.state tpane))
            id-tab (.hashCode tab)]
        (swap! (.state tpane) assoc :tab-prefs (dissoc tprefs id-tab))
        (when-let [pstmt (get-in @(.state tpane) [:pstmts id-tab])]
          (.close pstmt)
          (let [pstmts (:pstmts @(.state tpane))]
            (swap! (.state tpane) assoc :pstmts (dissoc pstmts id-tab))))))))

(defn- login [pref]
  (if-let [as (api/get-alert-status (:email pref) (:passwd pref))]
    (do (nau/add-alert-status as)
        (let [user-name (:user_name as) comms (apply hash-set (:comms as))]
        [user-name (pgm/get-sql-comm-id comms)]))
    ["login failed" nil]))

(defn- update-tab [tpane idx]
  (let [id-tab (.hashCode (.getTabComponentAt tpane idx))
        tab (.getTabComponentAt tpane idx)
        content (.getComponentAt tpane idx)
        title (get-in @(.state tpane) [:tab-titles id-tab])
        pstmt (get-in @(.state tpane) [:pstmts id-tab])]
;;    (if (and pstmt (not (.isClosed pstmt)))
    (if pstmt
      (let [npgms (pgm/search-pgms-by-pstmt pstmt)]
        (when (get-in @(.state tpane) [:tab-prefs id-tab :alert])
          (future
            (doseq [[id npgm] npgms] (when-not (:alerted npgm) (al/alert-pgm id)))))
        (.setPgms content npgms)
        (.setTitle tab (format "%s (%d)" title (count npgms))))
      (.setTitle tab (format "%s (-)" title)))))

(defn- update-tabs [tpane]
  (when (< 0 (pgm/count-pgms))
    (doseq [idx (range 1 (.getTabCount tpane))]
      (update-tab tpane idx))))

(defn- set-tab-statement [tpane tab sql]
  (let [id-tab (.hashCode tab) conn @(:conn @(.state tpane))]
    (when-let [old-pstmt (get-in @(.state tpane) [:pstmts id-tab])] (.close old-pstmt))
    (when conn
      (let [pstmt (jdbc/prepare-statement conn sql :concurrency :read-only)]
        (swap! (.state tpane) assoc-in [:pstmts id-tab] pstmt)))))

(defn- update-tab-title [tpane tab title]
  (swap! (.state tpane) update-in [:tab-titles (.hashCode tab)] (fn [_] title)))

(defn- update-tab-pref [tpane tab pref]
  (swap! (.state tpane) update-in [:tab-prefs (.hashCode tab)] (fn [_] pref))
  (let [idx (.indexOfTabComponent tpane tab)
        content (.getComponentAt tpane idx)]
    (condp = (:type pref)
      :comm (do (.setTitle tab "loading...")
                (update-tab-title tpane tab "not logged in")
                (let [[title sql] (login pref)]
                  (.setTitle tab title)
                  (update-tab-title tpane tab title)
                  (set-tab-statement tpane tab sql)))
      :kwd  (do (.setTitle tab (:title pref))
                (update-tab-title tpane tab (:title pref))
                (let [sql (pgm/get-sql-kwds (:query pref) (:target pref))]
                  (set-tab-statement tpane tab sql))))
    (.execute (:pool @(.state tpane)) #(update-tab tpane idx))))

(defn- etp-addTab [this pref]
  (when (or (= :kwd (:type pref)) (= :comm (:type pref)))
    (let [tab     (nico.ui.TabComponent. (:type pref))
          content (nico.ui.ProgramsPanel.)
          id-tab  (.hashCode tab)]
      (swap! (.state this) assoc-in [:tab-prefs id-tab] pref)
      (.addCloseButtonListener tab (confirm-rem-tab-fn this tab))
      (do-swing-and-wait
        (.addTabSuper this nil content))
      (do-swing-and-wait
        (.setTabComponentAt this (.indexOfComponent this content) tab))
      (update-tab-pref this tab pref))))

(defn- etp-addTabs [this prefs]
  (doseq [pref prefs] (.addTab this pref)))

(defn- tabmenu-items-aux [tpane tab]
  (let [pref (get-in @(.state tpane) [:tab-prefs (.hashCode tab)])]
    (condp = (:type pref)
      :comm (let [aitem (JCheckBoxMenuItem. "アラート" (:alert pref))
                  ritem (JMenuItem. "再ログイン")
                  eitem (JMenuItem. "編集")]
              (doto ritem
                (add-action-listener
                 (fn [e]
                   (update-tab-pref tpane tab pref))))
              (doto eitem
                (add-action-listener
                 (fn [e] (let [dlg (ukvd/user-password-dialog
                                    (.getTopLevelAncestor tpane) "ユーザー情報の編集" pref
                                    (fn [npref] (update-tab-pref tpane tab npref)))]
                           (do-swing (.setVisible dlg true))))))
              (doto aitem
                (add-action-listener
                 (fn [e] (let [val (.isSelected aitem)]
                           (update-tab-pref tpane tab (assoc pref :alert val))))))
              [aitem ritem eitem])
      :kwd (let [aitem (JCheckBoxMenuItem. "アラート" (:alert pref))
                 eitem (JMenuItem. "編集")]
             (doto eitem
               (add-action-listener
                (fn [e] (let [dlg (uktd/keyword-tab-dialog
                                   (.getTopLevelAncestor tpane) "番組検索設定の編集" pref
                                   (fn [npref] (update-tab-pref tpane tab npref)))]
                          (do-swing (.setVisible dlg true))))))
             (doto aitem
               (add-action-listener
                (fn [e] (let [val (.isSelected aitem)]
                          (update-tab-pref tpane tab (assoc pref :alert val))))))
             [aitem eitem])
      nil)))

(defn- close-statements [tpane]
  (doseq [pstmt (vals (:pstmts @(.state tpane)))] (.close pstmt))
  (swap! (.state tpane) assoc :pstmts {}))

(defn- etp-getTabMenuItems [this idx]
  (when-not (= 0 idx)
    (let [tab     (.getTabComponentAt this idx)
          content (.getComponentAt this idx)
          id-tab  (.hashCode tab)
          pref    (get-in @(.state this) [:tab-prefs id-tab])]
      (when-let [itms (tabmenu-items-aux this tab)]
	(let [items (atom itms)]
	  (letfn [(swap-fn
		   [idx nidx]
		   (fn [e]
		     (let [type (:type pref)]
		       (doto this
			 (.remove idx)
			 (.insertTab nil nil content nil nidx)
			 (.setTabComponentAt nidx tab)
			 (.setSelectedIndex nidx)))))]
	    (when (> (dec (.getTabCount this)) idx)
	      (swap! items conj
		     (doto (JMenuItem. "右へ") (add-action-listener (swap-fn idx (inc idx))))))
	    (when (< 1 idx)
	      (swap! items conj
		     (doto (JMenuItem. "左へ") (add-action-listener (swap-fn idx (dec idx))))))
	    (conj @items
		  (doto (JMenuItem. "削除")
		    (add-action-listener (confirm-rem-tab-fn this tab))))))))))

(defn- etp-getTabPrefs [this]
  (let [tprefs (:tab-prefs @(.state this))]
    (vec (map #(get tprefs (.hashCode (.getTabComponentAt this %))) (range 1 (.getTabCount this))))))

(defn- etp-post-init [this]
  (let [tpane this spanel (nico.ui.SearchPanel.)]
    (.setAddTabListener spanel (fn [ntpref] (.addTab tpane ntpref)))
    (doto tpane
      (.addTabSuper "検索" spanel)
      (.addMouseListener
       (proxy [MouseListener] []
	 (mouseClicked
	  [e]
	  (when (SwingUtilities/isRightMouseButton e)
	    (let [x (.getX e) y (.getY e) idx (.indexAtLocation tpane x y)]
	      (when-let [items (.getTabMenuItems tpane idx)]
		(let [pmenu (JPopupMenu.)]
		  (doseq [item items] (doto pmenu (.add item)))
		  (.show pmenu tpane (.getX e) (.getY e)))))))
	 (mouseEntered [e])
	 (mouseExited [e])
	 (mousePressed [e])
	 (mouseReleased [e])))))
  (let [[queue pool] (c/periodic-executor 1 TimeUnit/SECONDS 3)]
    (swap! (.state this) assoc :queue queue :pool pool))
  (pgm/add-pgms-hook :updated (fn [] (.execute (:pool @(.state this)) #(update-tabs this))))
  (nr/add-rss-hook :countdown (fn [count max]
                                (when (= 0 (rem count 3)) (.repaintTable (.getSelectedComponent this)))))
  (pgm/add-db-hook :shutdown (fn [] (close-statements this))))

