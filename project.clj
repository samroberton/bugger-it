(defproject bugger-it "0.1.0-SNAPSHOT"
  :description "Programmatic JDI-based debugging of code running in a separate JVM."
  :url "https://github.com/samroberton/bugger-it"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]]
  :main ^:skip-aot bugger-it.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
