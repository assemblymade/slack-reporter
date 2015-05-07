(defproject slack-reporter "0.1.0-SNAPSHOT"
  :description "Slack summaries for the Assembly Titan API"
  :url "https://github.com/assemblymade/slack-reporter"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/java.jdbc "0.3.6"]
                 [clj-http "1.1.0"]
                 [clj-time "0.9.0"]
                 [clojure-opennlp "0.3.3"]
                 [compojure "1.3.3"]
                 [com.taoensso/carmine "2.9.2"]
                 [environ "1.0.0"]
                 [korma "0.4.0"]
                 [org.flywaydb/flyway-core "3.2"]
                 [org.postgresql/postgresql "9.4-1201-jdbc41"]
                 [overtone/at-at "1.2.0"]
                 [ring/ring-defaults "0.1.4"]
                 [ring/ring-jetty-adapter "1.3.2"]]
  :min-lein-version "2.5.0"
  :plugins [[lein-environ "1.0.0"]
            [lein-ring "0.9.3"]]
  :main ^:skip-aot slack-reporter.web
  :uberjar-name "slack-reporter.standalone.jar"
  :profiles {:uberjar {:aot :all}}
  :repl-options {:timeout 360000}
  :ring {:handler slack-reporter.web/app}
  :aliases {"migrate" ["run" "-m" "slack-reporter.db.migrations/migrate"]})

