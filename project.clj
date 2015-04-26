(defproject slack-reporter "0.1.0-SNAPSHOT"
  :description "Slack summaries for the Assembly Titan API"
  :url "https://github.com/assemblymade/slack-reporter"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/data.json "0.2.6"]
                 [clj-http "1.1.0"]
                 [clojure-opennlp "0.3.3"]
                 [environ "1.0.0"]
                 [overtone/at-at "1.2.0"]
                 [ring/ring-jetty-adapter "1.3.2"]]
  :min-lein-version "2.5.0"
  :plugins [[lein-environ "1.0.0"]]
  :main ^:skip-aot slack-reporter.web
  :uberjar-name "slack-reporter.standalone.jar"
  :profiles {:uberjar {:aot :all}})
