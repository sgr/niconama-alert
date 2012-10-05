(defproject nicoalert "1.5.2"
  :description "Niconama Alert J"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/data.zip "0.1.1"]
                 [org.clojure/java.jdbc "0.2.3"]
                 [org.clojure/math.numeric-tower "0.0.1"]
                 [org.clojure/tools.logging "0.2.4"]
                 [clj-http "[0.5,)"]
                 [swing-utils "0.2.0"]
                 [enlive "1.0.1"]
                 [com.mchange/c3p0 "0.9.2-pre5"]
                 [com.h2database/h2 "[1.3,)"]]
  :dev-dependencies [[org.clojure/tools.nrepl "0.2.0-beta9"]]
;;  :dev-dependencies [[swank-clojure "1.4.2"]]
  :omit-source true
;;  :resource-paths ["resources"]
  :jar-exclusions [#"(?:^|/).svn" #"(?:^|/).git" #"(?:\w+).xcf$" #"(?:\w+).pdn$"]
  :main nico.core)

