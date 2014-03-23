;; -*- coding: utf-8-unix -*-
(ns nico.ui.prefs-dlg
  (:require [clojure.tools.logging :as log]
            [nico.ui.prefs-alert :as alert]
            [nico.ui.prefs-browsers :as browsers]
            [slide.core :as slc]
            [seesaw.bind :as sb]
            [seesaw.core :as sc]))

(defn dlg [prefs]
  (let [prefs-agent (agent prefs)
        apanel (alert/panel (:alert prefs) #(send prefs-agent assoc :alert %))
        bpanel (browsers/panel (:browsers prefs) #(send prefs-agent assoc :browsers %))
        pdlg (slc/dialog
              :title "Preferences"
              :content (sc/tabbed-panel
                        :tabs [{:title "Alert" :tip "Alert preference" :content apanel}
                               {:title "Browser" :tip "Browsers preference" :content bpanel}])
              :option-type :ok-cancel
              :success-fn (fn [_] @prefs-agent)
              :id-ok :btn-ok
              :on-close :dispose
              :modal? true)]

    (let [{:keys [btn-ok]} (sc/group-by-id pdlg)]
      (sb/bind prefs-agent
               (sb/transform
                (fn [new-prefs]
                  (let [{:keys [alert browsers]} new-prefs]
                    (log/infof "alert: %s" (pr-str alert))
                    (log/infof "browsers: %s" (pr-str browsers))
                    (or (not= (:alert prefs) alert)
                        (not= (:browsers prefs) browsers)))))
               (sb/property btn-ok :enabled?)))

    (-> pdlg
        sc/pack!)))
