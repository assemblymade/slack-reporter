(ns slack-reporter.web
  (:gen-class)
  (:require [clojure.data.json :as json]
            [compojure.core :refer [defroutes GET POST]]
            [environ.core :refer [env]]
            [overtone.at-at :as at-at]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.defaults :refer [api-defaults wrap-defaults]]
            [slack-reporter.core :refer [post-file-upload-highlight
                                         post-channel-highlight]]
            [slack-reporter.burst :as burst]))

(defonce twenty-four-hours (* 24 60 60 1000))

(defroutes app-routes
  (GET "/" [] {:status 200
               :headers {"Content-Type" "text/plain"}
               :body "ok"})
  (POST "/webhook" {params :params}
        (when (= (params :token) (env :slack-webhook-token))
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
    (core/refresh)
    (burst/simulate-bursts c)
    (at-at/every twenty-four-hours #(post-channel-highlight c) p)
    (run-jetty app {:port (Integer. (or (env :port) "8080"))})))
