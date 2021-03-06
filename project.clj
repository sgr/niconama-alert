(defproject nicoalert "1.7.6"
  :description "NiconamaAlert.clj"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.2.371"]
                 [org.clojure/data.zip "0.1.1"]
                 [org.clojure/java.jdbc "0.4.2"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [org.clojure/tools.logging "0.3.1"]
                 [seesaw "1.4.5"]
                 [slide "0.2.3"]
                 [http-kit "2.1.19"]
                 [config-file "0.1.0"]
                 [desktop-alert "0.5.2"]
                 [input-parser "0.1.1"]
                 [enlive "1.1.6"]
                 [org.xerial/sqlite-jdbc "3.8.11.2"]
                 [org.apache.commons/commons-lang3 "3.4"]
                 [net.sourceforge.htmlcleaner/htmlcleaner "2.15"]]
  :exclusions [org.apache.ant/ant]
  :profiles {:dev     {:resource-paths ["resources" "test-data"]}
             :debug   {:debug true
                       :injections [(prn (into {} (System/getProperties)))]}
             :uberjar {:debug false :aot :all}}
  :aot :all
  :debug false
  :omit-source true
  :source-paths ["src/main/clojure"]
  :java-source-paths ["src/main/java"]
  :javac-options ["-encoding" "UTF-8" "-Xlint:deprecation" "-Xlint:unchecked"]
  :resource-paths ["resources"]
  :test-paths ["src/test/clojure"]
  :test-selectors {:default (complement (fn [m] (some m [:stress :gui :net :data])))
                   :stress-core (fn [m] (every? m [:stress :core]))
                   :stress-rss (fn [m] (every? m [:stress :rss]))
                   :stress-db (fn [m] (every? m [:stress :db]))
                   :scrape (fn [m] (every? m [:scrape :data]))
                   :rss (fn [m] (every? m [:rss :data]))
                   :data :data
                   :net :net
                   :gui :gui
                   :all (constantly true)}
  :jvm-opts ~(let [sys (.toLowerCase (System/getProperty "os.name"))]
               (condp re-find sys
                 #"mac" ["-d32" "-Xdock:name=NikonamaAlert.clj" "-Xdock:icon=resources/dempakun.png"
                         ;;"-XX:+TieredCompilation" "-XX:TieredStopAtLevel=1"
                         ;; "-XX:+CMSClassUnloadingEnabled"
                         ;;"-XX:NewRatio=1" ;;"-XX:SurvivorRatio=8" "-XX:GCTimeRatio=20"
                         ;;"-XX:+UseParallelGC" "-XX:+UseParallelOldGC"
                         ;; "-XX:+UseConcMarkSweepGC" "-XX:+UseParNewGC"
                         ;; "-XX:+CMSIncrementalMode" "-XX:+CMSIncrementalPacing"
                         ;; "-XX:CMSIncrementalDutyCycleMin=0" "-XX:+CMSParallelRemarkEnabled"
                         "-XX:+UseG1GC" "-Xmx64M" "-XX:G1HeapRegionSize=4M"
                         ]
                 []))
  ;; Oracle's Java SE 7/8 for Mac OS X has a serious memory-leak bug.
  ;; <https://bugs.openjdk.java.net/browse/JDK-8029147>
  :java-cmd ~(let [sys (.toLowerCase (System/getProperty "os.name"))]
               (condp re-find sys
                 #"mac" "/Library/Java/JavaVirtualMachines/1.6.0.jdk/Contents/Home/bin/java"
                 (or (System/getenv "JAVA_CMD") "java")))
  :main nico.core)
