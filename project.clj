(defproject nicoalert "1.6.1"
  :description "Niconama Alert J"
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/data.zip "0.1.1"]
                 [org.clojure/java.jdbc "0.3.0-alpha3"]
                 [org.clojure/math.numeric-tower "0.0.2"]
                 [org.clojure/tools.logging "0.2.6"]
                 [logutil "[0.2,)"]
                 [clj-http "0.7.1"]
                 [org.apache.httpcomponents/httpclient-cache "4.2.5"]
                 [swing-utils "0.2.0"]
                 [enlive "1.0.1"]
                 [org.xerial/sqlite-jdbc "3.7.2"]
                 [org.apache.commons/commons-lang3 "3.1"]
                 [net.sourceforge.htmlcleaner/htmlcleaner "2.4"]]
  :exclusions [org.apache.ant/ant]
  :debug false
  :profiles {:dev
             {:dependencies [[org.clojure/tools.nrepl "[0.2,)"]]
              :debug true
              :warn-on-reflection true}
             :debug
             {:debug true
              :warn-on-reflection true
              :injections [(prn (into {} (System/getProperties)))]}}
  :omit-source true
  :resource-paths ["resources"]
  :jar-exclusions [#"(?:^|/).svn" #"(?:^|/).git" #"(?:\w+).xcf$" #"(?:\w+).pdn$"]
  :repositories [["sonatype-oss-public" {:url "https://oss.sonatype.org/content/groups/public/"}]]
  :main nico.core)
