(defproject nicoalert "1.0.0-beta"
  :description "FIXME: write"
  :dependencies [[org.clojure/clojure "1.2.0"]
                 [org.clojure/clojure-contrib "1.2.0"]
		 [org.swinglabs/swingx "1.6.1"]
;		 [org.apache.derby/derby "10.6.2.1"]]
		 [org.apache.derby/derbyclient "10.6.2.1"]]
  :dev-dependencies [[swank-clojure "1.2.0"]]
  :omit-source true
  :resources-path "resource"
  :jar-exclusions [#"(?:^|/).svn" #"(?:^|/).git" #"(?:\w+).xcf$" #"(?:\w+).pdn$"]
  :main nico.core)

