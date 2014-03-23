;; -*- coding: utf-8-unix -*-
(ns nico.ui.menu
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [config-file :as cf]
            [seesaw.core :as sc]
            [seesaw.util :as su])
  (:import [java.lang.reflect InvocationHandler Proxy]))

(defn- get-app-mac []
  (let [cApp (Class/forName "com.apple.eawt.Application")
        mGetApplication (.getMethod cApp "getApplication" nil)]
    [cApp (.invoke mGetApplication nil nil)]))

(defn- impl-interface [cInterface method-map]
  (Proxy/newProxyInstance
   (.getClassLoader cInterface) (into-array Class [cInterface])
   (proxy [InvocationHandler] []
     (invoke [proxy method args]
       (when-let [f (get method-map (.getName method))]
         (f nil))))))

(defn- reg-app-handlers-mac [about-fn exit-fn alert-config-fn]
  (try
    (let [[cApp app] (get-app-mac)
          cAboutHandler (Class/forName "com.apple.eawt.AboutHandler")
          cQuitHandler (Class/forName "com.apple.eawt.QuitHandler")
          cPreferencesHandler (Class/forName "com.apple.eawt.PreferencesHandler")
          mSetAboutHandler (.getMethod cApp "setAboutHandler" (into-array Class [cAboutHandler]))
          mSetQuitHandler (.getMethod cApp "setQuitHandler" (into-array Class [cQuitHandler]))
          mSetPreferencesHandler (.getMethod cApp "setPreferencesHandler" (into-array Class [cPreferencesHandler]))
          aboutHandler (impl-interface cAboutHandler {"handleAbout" about-fn})
          quitHandler (impl-interface cQuitHandler {"handleQuitRequestWith" exit-fn})
          preferencesHandler (impl-interface cPreferencesHandler {"handlePreferences" alert-config-fn})]
      (.invoke mSetAboutHandler app (to-array [aboutHandler]))
      (.invoke mSetQuitHandler app (to-array [quitHandler]))
      (.invoke mSetPreferencesHandler app (to-array [preferencesHandler])))
    (catch Exception e
      (log/warn e "This platform doesn't support eawt"))))

(let [add-user-channel-item (sc/menu-item :id :add-user-channel-item :text "Add user channel")
      view-log-item (sc/menu-item :id :view-log-item :text "View Application Log")]

  (defn- menu-bar-mac [about-fn exit-fn prefs-fn]
    (reg-app-handlers-mac about-fn exit-fn prefs-fn)
    (sc/menubar
     :items [(sc/menu :text "Channel"
                      :items [add-user-channel-item])
             (sc/menu :text "Tool"
                      :items [view-log-item])]))

  (defn- menu-bar-aux [about-fn exit-fn prefs-fn]
    (let [about-item (sc/menu-item :text "About")
          exit-item (sc/menu-item :text "Exit"
                                  :mnemonic (su/to-mnemonic-keycode \X)
                                  :key "ctrl Q")
          prefs-item (sc/menu-item :text "Preferences"
                                   :mnemonic (su/to-mnemonic-keycode \A))]
      (sc/listen about-item :action about-fn)
      (sc/listen exit-item :action exit-fn)
      (sc/listen prefs-item :action prefs-fn)

      (sc/menubar
       :items [(sc/menu :text "File"
                        :mnemonic (su/to-mnemonic-keycode \F)
                        :items [prefs-item exit-item])
               (sc/menu :text "Channel"
                        :mnemonic (su/to-mnemonic-keycode \C)
                        :items [add-user-channel-item])
               (sc/menu :text "Help"
                        :mnemonic (su/to-mnemonic-keycode \H)
                        :items [view-log-item about-item])]))))

(defn menu-bar [about-fn exit-fn prefs-fn]
  (condp = (cf/system)
    :mac (menu-bar-mac about-fn exit-fn prefs-fn)
    (menu-bar-aux about-fn exit-fn prefs-fn)))
