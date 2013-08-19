;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "main frame"}
  nico.ui.main-frame
  (:use [clojure.tools.swing-utils :only [do-swing-and-wait]]
        [nico.ui.control-panel :only [control-panel]])
  (:require [nico.prefs :as p]
            [nico.pgm :as pgm]
            [nico.ui.ext-tabbed-pane :as etp]
            [nico.ui.menu :as m])
  (:import [java.awt BorderLayout Dimension Font]
           [java.awt.event WindowEvent WindowListener]
           [javax.swing JFrame ImageIcon ToolTipManager UIManager]))

(def ^{:private true} F-KEYS ["Button.font" "Menu.font" "MenuItem.font" "ColorChooser.font" "CheckBox.font"
                              "CheckBoxMenuItem.font" "ToggleButton.font" "Label.font"
                              "FormattedTextField.font" "Spinner.font" "PasswordField.font"
                              "List.font" "TableHeader.font" "ComboBox.font"])

(defn ^JFrame main-frame [add-hook-fn closing-fn]
  (System/setProperty "apple.laf.useScreenMenuBar" "true")
  (UIManager/setLookAndFeel (UIManager/getSystemLookAndFeelClassName))
  (UIManager/put "OptionPane.okButtonText" "OK")
  (UIManager/put "OptionPane.cancelButtonText" "キャンセル")
  (doseq [k F-KEYS] (UIManager/put k (Font. "Default" Font/PLAIN 12)))
  (let [appicn (ImageIcon. (clojure.java.io/resource "dempakun.png"))
        pref @(p/get-pref)
        frame (JFrame. p/APP-TITLE)
        tabbed-pane (nico.ui.ExtTabbedPane.)
        mbar (m/menu-bar frame tabbed-pane)
        tm (ToolTipManager/sharedInstance)
        cpane (.getContentPane frame)
        cpanel (control-panel)]
    (.addTabs tabbed-pane (:tabs pref))
    (doto tm (.setInitialDelay 100))
    (-> frame .getRootPane (.setJMenuBar mbar))
    (doto frame
      (.addWindowListener
       (proxy [WindowListener][]
         (windowClosing
           [e]
           (let [w (.getWidth frame), h (.getHeight frame)
                 p (.getLocationOnScreen frame), tp (.getTabPrefs tabbed-pane)]
             (do-swing-and-wait (.setVisible frame false))
             (add-hook-fn (fn []
                            (swap! (p/get-pref)
                                   assoc :window {:width w :height h :posx (.x p) :posy (.y p)} :tabs tp)
                            (p/store-pref)))
             (closing-fn)
             (do-swing-and-wait (.dispose frame))))
         (windowClosed [e] (System/exit 0))
         (windowOpened [e])
         (windowIconified [e])
         (windowDeiconified [e])
         (windowActivated [e])
         (windowDeactivated [e])))
      (.setIconImage (.getImage appicn))
      (.setDefaultCloseOperation JFrame/DO_NOTHING_ON_CLOSE)
      (.add tabbed-pane BorderLayout/CENTER)
      (.add cpanel BorderLayout/SOUTH)
;;      (.setLayout layout) (.add cpanel) (.add tabbed-pane)
      (.setSize (-> pref :window :width) (-> pref :window :height))
      (.setLocation (-> pref :window :posx) (-> pref :window :posy)))))

