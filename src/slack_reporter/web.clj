(ns slack-reporter.web
  (:gen-class)
  (:require [environ.core :refer [env]]
            [overtone.at-at :as at-at]
            [ring.adapter.jetty :as ring]
            [slack-reporter.core :refer [post-file-upload-highlight
                                         post-channel-highlight]]))

(defonce twelve-hours (* 12 60 60 1000))

(defn handler [request]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "I'm sorry, Dave, I'm afraid I can't do that."})

(defn -main []
  (let [p (at-at/mk-pool)]
    (at-at/every twelve-hours #((post-channel-highlight
                                (env :target-channel))
                                (post-file-upload-highlight
                                (env :target-channel)))
                 p)
    (ring/run-jetty handler {:port (or (env :port) 8080)})))
