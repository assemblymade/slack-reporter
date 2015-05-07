(ns slack-reporter.db.migrations
  (:import [org.flywaydb.core Flyway])
  (:require [environ.core :refer [env]]
            [slack-reporter.db.config :refer [get-datasource]]))

(defn migrate []
  (let [datasource (get-datasource (env :database-url))
        flyway (doto (Flyway.)
                 (.setDataSource datasource)
                 (.setSqlMigrationPrefix ""))]
    (.migrate flyway)))
