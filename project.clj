(defproject nicoalert "1.6.1"
  :description "Niconama Alert J"
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/data.zip "0.1.1"]
                 [org.clojure/java.jdbc "0.3.0-alpha4"]
                 [org.clojure/math.numeric-tower "0.0.2"]
                 [org.clojure/tools.logging "0.2.6"]
                 [org.apache.httpcomponents/httpclient-cache "4.2.5"]
                 [logutil "[0.2,)"]
                 [slide "[0.1,)"]
                 [clj-http "0.7.3"]
                 [config-file "0.1.0"]
                 [desktop-alert "[0.3,)"]
                 [input-parser "0.1.1"]
                 [swing-utils "0.2.0"]
                 [enlive "1.1.1"]
                 [org.xerial/sqlite-jdbc "3.7.15-M1"]
                 [org.apache.commons/commons-lang3 "3.1"]
                 [net.sourceforge.htmlcleaner/htmlcleaner "2.6"]]
  :exclusions [org.apache.ant/ant]
  :debug false
  :global-vars {*warn-on-reflection* true
                *assert* false}
  :profiles {:dev
             {:dependencies [[org.clojure/tools.nrepl "[0.2,)"]]
              :debug true}
             :debug
             {:debug true
              :injections [(prn (into {} (System/getProperties)))]}}
  :aot :all
  :omit-source true
  :source-paths ["src/main/clojure"]
  :java-source-paths ["src/main/java"]
  :resource-paths ["resources" "src/main/resource"]
  :test-paths ["src/test/clojure"]
  :jar-exclusions [#"(?:^|/).svn" #"(?:^|/).git" #"(?:\w+).xcf$" #"(?:\w+).pdn$"]
;;  :repositories [["sonatype-oss-public" {:url "https://oss.sonatype.org/content/groups/public/"}]]
  :jvm-opts ~(let [sys (.toLowerCase (System/getProperty "os.name"))]
               (condp re-find sys
                 #"mac" ["-Xdock:name=NikonamaAlert.clj" "-Xdock:icon=resources/dempakun.png" "-XX:+TieredCompilation" "-XX:TieredStopAtLevel=1"]
                 ["-XX:+TieredCompilation" "-XX:TieredStopAtLevel=1"]))
  ;; In Mac, Java SE 7u25 consume memory to excess.
  :java-cmd ~(let [sys (.toLowerCase (System/getProperty "os.name"))]
               (condp re-find sys
                 #"mac" "/System/Library/Java/JavaVirtualMachines/1.6.0.jdk/Contents/Home/bin/java"
                 (or (System/getenv "JAVA_CMD") "java")))
  :main nico.core)
