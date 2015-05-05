(ns slack-reporter.replay
  (:require [slack-reporter.redis :refer [with-car]]
            [slack-reporter.reporter :refer [post-highlight]]
            [slack-reporter.util :refer [now]]
            [taoensso.carmine :as car]))

(defn expiration []
  (+ (now) (* 48 60 60)))

(defn two-days-ago []
  (- (now) (* 48 60 60)))

(defn add
  "Upserts a highlight or highlights {h} to the {k}:replay
  sorted set with a score of the Unix time (in seconds) 
  two days from the time of its insertion. This lets us
  expire stale members of the set easily based on their
  score."
  [k h]
  (let [key (str k ":replay")]
    (with-car
      (car/zadd key (expiration) h)
      (car/zremrangebyscore key "-inf" (two-days-ago)))))

(defn fetch
  "Fetches a range of highlights in the {k}:replay sorted set."
  [k]
  (let [key (str k ":replay")]
    (with-car (car/zrangebyscore key (two-days-ago) "inf"))))
