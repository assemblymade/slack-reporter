(ns slack-reporter.web
  (:require [environ.core :refer [env]]
            [overtone.at-at :as at-at]
            [ring.adapter.jetty :as ring]
            [slack-reporter.core :refer [post-file-upload-highlight
                                         post-channel-highlight]]))

(defonce twelve-hours (* 12 60 60 1000))

(def p (at-at/mk-pool))

(at-at/every twelve-hours #((post-channel-highlight
                             (env :target-channel))
                            (post-file-upload-highlight
                             (env :target-channel)))
             p)

(defn handler [request]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "I'm sorry, I'm afraid I can't let you do that, Dave."})

(defn -main []
  (ring/run-jetty handler (or (env :port) "8080")))
