(ns slack-reporter.seed
  (:require [slack-reporter.core :as core]))

(defn important-comments
  ([c]
   (core/post-channel-highlights c 3))
  ([c n]
   (core/post-channel-highlights c n)))

(defn bursts
  "Detects bursts in channel {c} according to the 
  leaky bucket algorithm in slack-reporter/burst 
  and posts highlights about them if any are 
  detected."
  [c])

