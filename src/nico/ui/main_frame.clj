;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "main frame"}
  nico.ui.main-frame
  (:use [clojure.contrib.swing-utils :only [do-swing do-swing*]]
	[nico.ui.ext-tabbed-pane :only [ext-tabbed-pane add-all-tab add-tab]])
  (:require [nico.prefs :as p]
	    [nico.ui.fetch-panel :as ufp]
	    [nico.ui.menu :as m])
  (:import (java.awt Dimension Font)
	   (java.awt.event WindowEvent WindowListener)
	   (javax.swing JFrame ImageIcon SpringLayout ToolTipManager UIManager)))

(def *fkeys* ["Button.font" "Menu.font" "MenuItem.font" "ColorChooser.font" "CheckBox.font"
	      "CheckBoxMenuItem.font" "ToggleButton.font" "Label.font"
	      "FormattedTextField.font" "Spinner.font" "PasswordField.font"
;	      "ProgressBar.font"
	      "List.font" "TableHeader.font" "ComboBox.font"])

(defn main-frame []
  (doseq [k *fkeys*] (UIManager/put k (Font. "Default" Font/PLAIN 12)))
  (let [appicn (ImageIcon. (.getResource (.getClassLoader (class (fn []))) "dempakun.png"))
	pref @(p/get-pref)
	frame (JFrame. "NicoNama Alert (J)")
	tabbed-pane (ext-tabbed-pane)
	mbar (m/menu-bar frame tabbed-pane)
	tm (ToolTipManager/sharedInstance)
	cpane (.getContentPane frame)
	fetch-panel (ufp/fetch-panel tabbed-pane)
	layout (SpringLayout.)]
    (add-all-tab tabbed-pane)
    (doseq [tab-pref (:tabs pref)] (add-tab tabbed-pane tab-pref))
    (doto layout
      (.putConstraint SpringLayout/NORTH mbar 0 SpringLayout/NORTH cpane)
      (.putConstraint SpringLayout/WEST mbar 0 SpringLayout/WEST cpane)
      (.putConstraint SpringLayout/EAST mbar -0 SpringLayout/EAST cpane)
      (.putConstraint SpringLayout/NORTH tabbed-pane 0 SpringLayout/SOUTH mbar)
      (.putConstraint SpringLayout/WEST tabbed-pane 5 SpringLayout/WEST cpane)
      (.putConstraint SpringLayout/EAST tabbed-pane -5 SpringLayout/EAST cpane)
      (.putConstraint SpringLayout/SOUTH tabbed-pane -5 SpringLayout/NORTH fetch-panel)
      (.putConstraint SpringLayout/WEST fetch-panel 5 SpringLayout/WEST cpane)
      (.putConstraint SpringLayout/EAST fetch-panel -5 SpringLayout/EAST cpane)
      (.putConstraint SpringLayout/SOUTH fetch-panel -5 SpringLayout/SOUTH cpane))
    (doto tm (.setInitialDelay 100))
    (doto frame
      (.addWindowListener
       (proxy [WindowListener][]
	 (windowClosing
	  [e]
	  (let [w (.getWidth frame), h (.getHeight frame)
		p (.getLocationOnScreen frame), tp (.getTabPrefs tabbed-pane)]
	    (swap! (p/get-pref) assoc :window {:width w :height h :posx (.x p) :posy (.y p)} :tabs tp)
	    (p/store-pref)
	    (do-swing* :now (fn [] (.setVisible frame false) (.dispose frame)))))
	 (windowClosed [e] (System/exit 0))
	 (windowOpened [e])
	 (windowIconified [e])
	 (windowDeiconified [e])
	 (windowActivated [e])
	 (windowDeactivated [e])))
      (.setIconImage (.getImage appicn))
      (.setDefaultCloseOperation JFrame/DO_NOTHING_ON_CLOSE)
      (.setLayout layout) (.add mbar) (.add fetch-panel) (.add tabbed-pane)
      (.setSize (-> pref :window :width) (-> pref :window :height))
      (.setLocation (-> pref :window :posx) (-> pref :window :posy)))))

