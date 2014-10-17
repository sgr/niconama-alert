(defproject nicoalert "1.7.2"
  :description "NiconamaAlert.clj"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/data.zip "0.1.1"]
                 [org.clojure/java.jdbc "0.3.5"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [org.clojure/tools.logging "0.3.1"]
                 [seesaw "1.4.4"]
                 [slide "0.2.2"]
                 [clj-http "1.0.0"]
                 [config-file "0.1.0"]
                 [desktop-alert "0.5.2"]
                 [input-parser "0.1.1"]
                 [enlive "1.1.5"]
                 [org.xerial/sqlite-jdbc "3.8.6"]
                 ;;[org.xerial/sqlite-jdbc "3.7.15-M1"]
                 [org.apache.commons/commons-lang3 "3.3.2"]
                 [net.sourceforge.htmlcleaner/htmlcleaner "2.9"]]
  :exclusions [org.apache.ant/ant]
  :profiles {:dev
             {:resource-paths ["resources" "test-data"]}
             :debug
             {:debug true
              :injections [(prn (into {} (System/getProperties)))]}}
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
;;  :repositories [["sonatype-oss-public" {:url "https://oss.sonatype.org/content/groups/public/"}]]
  :jvm-opts ~(let [sys (.toLowerCase (System/getProperty "os.name"))]
               (condp re-find sys
                 ;; "sqlite-3.8.x-i386-libsqlitejdbc.jnilib" is as corrupt as ever :-(
                 ;; <https://bitbucket.org/xerial/sqlite-jdbc/issue/127/sqlite-jdbc4-382-snapshotjar-misses-org>
                 ;; <https://bitbucket.org/xerial/sqlite-jdbc/issue/147/javalangnoclassdeffounderror-org-sqlite>
                 ;;#"mac" ["-d32" "-Xdock:name=NikonamaAlert.clj" "-Xdock:icon=resources/dempakun.png"
                 #"mac" ["-Xdock:name=NikonamaAlert.clj" "-Xdock:icon=resources/dempakun.png"
                         "-XX:+TieredCompilation" "-XX:TieredStopAtLevel=1"]
                 ["-XX:+TieredCompilation" "-XX:TieredStopAtLevel=1"]))
  ;; Oracle's Java SE 7/8 for Mac OS X has a serious memory-leak bug.
  ;; <https://bugs.openjdk.java.net/browse/JDK-8029147>
  :java-cmd ~(let [sys (.toLowerCase (System/getProperty "os.name"))]
               (condp re-find sys
                 #"mac" "/System/Library/Java/JavaVirtualMachines/1.6.0.jdk/Contents/Home/bin/java"
                 (or (System/getenv "JAVA_CMD") "java")))
  :main nico.core)
