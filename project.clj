(defproject nicoalert "1.0.1"
  :description "Niconama Alert J"
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [org.clojure/clojure-contrib "1.2.0"]
		 [enlive "1.0.0"]]
  :dev-dependencies [[swank-clojure "1.3.1"]]
  :omit-source true
  :resources-path "resource"
  :jar-exclusions [#"(?:^|/).svn" #"(?:^|/).git" #"(?:\w+).xcf$" #"(?:\w+).pdn$"]
  :main nico.core)

