(ns slack-reporter.seed
  (:require [clj-http.client :as client]
            [environ.core :refer [env]]
            [slack-reporter.core :as core]
            [slack-reporter.burst :as burst]))

(defn important-comments
  ([c]
   (core/post-channel-highlights c 3))
  ([c n]
   (core/post-channel-highlights c n)))

(defn bursts
  ([]
   (burst/replay-bursts "burst"))
  ([c]
   (burst/replay-bursts c)))

(defn- refresh []
  (client/delete (str (env :titan-api-url) "/reporter")
                 {:basic-auth [(env :reporter-name)
                               (env :reporter-password)]})
  (burst/simulate-bursts (env :target-channel))
  (important-comments (env :target-channel)))
