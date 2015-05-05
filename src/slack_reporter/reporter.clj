(ns slack-reporter.reporter
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [environ.core :refer [env]]))

(defn post-highlight [highlight]
  (when (not (nil? highlight))
    (client/post (str (env :titan-api-url) "/changelogs/assembly/highlights")
                 {:basic-auth [(env :reporter-name)
                               (env :reporter-password)]
                  :body (json/write-str highlight)
                  :content-type :json})))
