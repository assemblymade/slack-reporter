(ns slack-reporter.web
  (:gen-class)
  (:require [clojure.data.json :as json]
            [compojure.core :refer [defroutes GET POST]]
            [environ.core :refer [env]]
            [korma.core :refer [limit select where]]
            [overtone.at-at :as at-at]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.defaults :refer [api-defaults wrap-defaults]]
            [slack-reporter.burst :as burst]
            [slack-reporter.core :as core :refer [post-file-upload-highlight
                                                  post-channel-highlight]]
            [slack-reporter.db.config :refer [webhooks]]
            [slack-reporter.db.migrations :refer [migrate]]))

(defonce twenty-four-hours (* 24 60 60 1000))

(defn- find-channel-by-token [t]
  (if-let [w (first (select webhooks (where {:token t})))]
    (w :channel)
    "Not found"))

(defroutes app-routes
  (GET "/" [] {:status 200
               :headers {"Content-Type" "text/plain"}
               :body "ok"})
  (POST "/webhook" {params :params}
        (when (= (params :channel_id) (find-channel-by-token (params :token)))
          (burst/add (params :channel_id) (dissoc params :token))
          (burst/burst? (params :channel_id) (Integer. (env :bucket-size)))
          {:status 200
           :headers {"Content-Type" "text/plain"}
           :body "ok"})))

(def app (wrap-defaults app-routes
                        (assoc api-defaults
                          :keywordize true)))

(defn -main []
  (let [p (at-at/mk-pool)
        c (env :target-channel)]
    (migrate)
    (core/refresh)
    (burst/simulate-bursts c)
    (at-at/every twenty-four-hours #(post-channel-highlight c) p)
    (run-jetty app {:port (Integer. (or (env :port) "8080"))})))
