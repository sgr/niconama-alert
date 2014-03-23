;; -*- coding: utf-8-unix -*-
(ns nico.ui.about-dlg
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [nico.config :as config]
            [seesaw.core :as sc]
            [seesaw.font :as sf]
            [seesaw.mig :as sm])
  (:import [java.awt Color]
           [java.net URI]
           [com.github.sgr.slide Link MultiLineLabel]))

(def LIBS
  [{:title "Clojure"
    :url "http://clojure.org/"
    :copyright "Copyright (c) Rich Hickey. All rights reserved."
    :lic "Eclipse Public License 1.0"
    :lic-url "http://opensource.org/licenses/eclipse-1.0.php"}
   {:title "core.async"
    :url "https://github.com/clojure/core.async"
    :copyright "Copyright (c) 2013 Rich Hickey and contributors"
    :lic "Eclipse Public License 1.0"
    :lic-url "http://opensource.org/licenses/eclipse-1.0.php"}
   {:title "clojure.data.zip"
    :url "https://github.com/clojure/data.zip/"
    :copyright "Copyright (c) Aaron Bedra, 2011-2012. All rights reserved."
    :lic "Eclipse Public License 1.0"
    :lic-url "http://opensource.org/licenses/eclipse-1.0.php"}
   {:title "clojure.java.jdbc"
    :url "https://github.com/clojure/java.jdbc"
    :copyright "Copyright (c) Sean Corfield, Stephen Gilardi, 2011-2013. All rights reserved."
    :lic "Eclipse Public License 1.0"
    :lic-url "http://opensource.org/licenses/eclipse-1.0.php"}
   {:title "math.numeric-tower"
    :url "https://github.com/clojure/math.numeric-tower"
    :copyright "Mark Engelberg"
    :lic "Eclipse Public License 1.0"
    :lic-url "http://opensource.org/licenses/eclipse-1.0.php"}
   {:title "tools.logging"
    :url "https://github.com/clojure/tools.logging"
    :copyright "Copyright (c) 2009 Alex Taggart"
    :lic "Eclipse Public License 1.0"
    :lic-url "http://opensource.org/licenses/eclipse-1.0.php"}
   {:title "clj-http"
    :url "https://github.com/dakrone/clj-http/"
    :copyright "Copyright (c) 2013 M. Lee Hinman"
    :lic "MIT License"
    :lic-url "https://github.com/dakrone/clj-http/blob/master/LICENSE"}
   {:title "Commons Lang"
    :url "http://commons.apache.org/proper/commons-lang/"
    :copyright "Copyright 2001-2013 The Apache Software Foundation"
    :lic "the Apache License 2.0"
    :lic-url "http://www.apache.org/licenses/LICENSE-2.0.html"}
   {:title "Enlive"
    :url "https://github.com/cgrand/enlive"
    :copyright "Christophe Grand"
    :lic "Eclipse Public License 1.0"
    :lic-url "http://opensource.org/licenses/eclipse-1.0.php"}
   {:title "HtmlCleaner"
    :url "http://htmlcleaner.sourceforge.net/"
    :copyright "Copyright (c) 2006-2014, HtmlCleaner team. All rights reserved."
    :lic "BSD License"
    :lic-url "http://htmlcleaner.sourceforge.net/license.php"}
   {:title "seesaw"
    :url "http://seesaw-clj.org/"
    :copyright "Copyright (C) 2012 Dave Ray"
    :lic "Eclipse Public License 1.0"
    :lic-url "http://opensource.org/licenses/eclipse-1.0.php"}
   {:title "SQLite JDBC Driver"
    :url "https://bitbucket.org/xerial/sqlite-jdbc"
    :copyright "Taro L. Saito"
    :lic "Apache License version 2.0"
    :lic-url "http://www.apache.org/licenses/"}
   {:title "config-file"
    :url "https://github.com/sgr/config-file"
    :copyright "Copyright (C) Shigeru Fujiwara All Rights Reserved."
    :lic "Eclipse Public License 1.0"
    :lic-url "http://opensource.org/licenses/eclipse-1.0.php"}
   {:title "desktop-alert"
    :url "https://github.com/sgr/desktop-alert"
    :copyright "Copyright (C) Shigeru Fujiwara All Rights Reserved."
    :lic "Eclipse Public License 1.0"
    :lic-url "http://opensource.org/licenses/eclipse-1.0.php"}
   {:title "input-parser"
    :url "https://github.com/sgr/input-parser"
    :copyright "Copyright (C) Shigeru Fujiwara All Rights Reserved."
    :lic "Eclipse Public License 1.0"
    :lic-url "http://opensource.org/licenses/eclipse-1.0.php"}
   {:title "logutil"
    :url "https://github.com/sgr/logutil"
    :copyright "Copyright (C) Shigeru Fujiwara All Rights Reserved."
    :lic "Eclipse Public License 1.0"
    :lic-url "http://opensource.org/licenses/eclipse-1.0.php"}
   {:title "Slide"
    :url "https://github.com/sgr/slide"
    :copyright "Copyright (C) Shigeru Fujiwara All Rights Reserved."
    :lic "Eclipse Public License 1.0"
    :lic-url "http://opensource.org/licenses/eclipse-1.0.php"}])

(letfn [(concat-libs [libs]
          (loop [lib-str nil, links [], libs libs]
            (if-let [lib (first libs)]
              (let [start-title (count lib-str)
                    end-title (+ start-title (count (:title lib)))
                    start-lic (+ end-title 3 (count (:copyright lib)))
                    end-lic (+ start-lic (count (:lic lib)))]
                (recur (str lib-str (:title lib) " " (:copyright lib) " (" (:lic lib) ")" (System/getProperty "line.separator"))
                       (conj links
                             (Link. start-title end-title (URI. (:url lib)))
                             (Link. start-lic end-lic (URI. (:lic-url lib))))
                       (rest libs)))
              [lib-str (into-array Link links)])))
        (libs-label [libs]
          (let [[text links] (concat-libs libs)]
            (doto (MultiLineLabel.)
              (.setLinkColor Color/BLUE)
              (.setText text links))))]

  (defn about-dlg []
    (let [lfont (sf/font :name :sans-serif :style :bold :size 16)
          title-label (doto (sc/label (format "%s %s" config/APP-TITLE (config/app-version-str)))
                        (.setFont lfont))
          libs-label (libs-label LIBS)
          libs (doto (sc/scrollable libs-label
                                    :hscroll :never
                                    :border "dependencies")
                 #(.setOpaque (.getViewport %) false)
                 (.setOpaque false))]

      (-> (sc/dialog
           :title "About this software"
           :content (sm/mig-panel
                     :constraints ["wrap 2, ins 0 0 0 0, fill" "[:300:][:130:]" "[:130:][:200:]"]
                     :items [[title-label "align center"] [(sc/label :icon (sc/icon "dempakun_small.png"))]
                             [libs "span 2, grow"]])
           :option-type :default
           :on-close :dispose
           :modal? true)
          sc/pack!))))

