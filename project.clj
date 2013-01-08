(defproject nicoalert "1.6.0"
  :description "Niconama Alert J"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/data.zip "0.1.1"]
                 [org.clojure/java.jdbc "0.2.3"]
                 [org.clojure/math.numeric-tower "0.0.2"]
                 [org.clojure/tools.logging "0.2.4"]
                 [clj-http "[0.6,)"]
                 [org.apache.httpcomponents/httpclient-cache "4.2.2"]
                 [swing-utils "0.2.0"]
                 [enlive "1.0.1"]
                 [org.xerial/sqlite-jdbc "3.7.2"]
                 [net.sourceforge.htmlcleaner/htmlcleaner "2.2"]]
  :exclusions [org.apache.ant/ant]
  :debug false
  :profiles {:dev
             {:dependencies [[org.clojure/tools.nrepl "0.2.0-RC1"]]
              :debug true
              :warn-on-reflection true}
             :debug
             {:debug true
              :warn-on-reflection true
              :injections [(prn (into {} (System/getProperties)))]}}
  :omit-source true
  :resource-paths ["resources"]
  :jar-exclusions [#"(?:^|/).svn" #"(?:^|/).git" #"(?:\w+).xcf$" #"(?:\w+).pdn$"]
  :main nico.core)
