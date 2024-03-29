(ns slack-reporter.burst
  (:require [clojure.data.json :as json]
            [clojure.string :as string]
            [environ.core :refer [env]]
            [slack-reporter.core :as core]
            [slack-reporter.db.config :refer [messages]]
            [slack-reporter.redis :refer [with-car]]
            [slack-reporter.replay :as replay]
            [slack-reporter.reporter :refer [post-highlight]]
            [slack-reporter.util :as util :refer [now]]
            [taoensso.carmine :as car])
  (:use [korma.core]))

(def five-minutes (* 5 60))
(def ten-minutes (* 2 five-minutes))

(defn add [key message]
  (with-car (car/zadd key (now) message))
  (insert messages
          (values {:channel-id key
                   :message message})))

(defn last-burst-at
  ([k]
   (with-car (car/get (str k ":last-sent"))))
  ([k v]
   (with-car (car/set (str k ":last-sent") v))))

(defn wait-for
  ([k]
   (with-car (car/get (str k ":wait-for"))))
  ([k v]
   (with-car (car/set (str k ":wait-for") v))))

(defn empty-bucket [k start stop]
  (with-car (car/zremrangebyscore k start stop)))

(defn get-channels []
  (clojure.walk/keywordize-keys (core/get-channels)))

(defn get-users []
  (map core/transform-user (core/get-users)))

(defn participants [msgs]
  (let [m (map #(% :user_name) msgs)
        f (frequencies m)]
    (into (sorted-set-by
           #(compare [(get f %2) %2]
                     [(get f %1) %1]))
          m)))

(defn participant-string [msgs username]
  (let [p (filter #(not= % username)
                  (participants msgs))]
    (cond
     (= (count p) 1) (first p)
     (= (count p) 2) (string/join " and " p)
     (= (count p) 3) (str (first p) ", " (second p) ", and " (last p))
     (> (count p) 3) (let [r (- (count p) 2)]
                       (str (string/join ", " (take 2 p))
                            ", and "
                            r
                            (if (= r 1)
                              " other"
                              " others"))))))

(defn create-highlight [messages]
  (if-let [message (first messages)]
    (if-let [raw-text (message :text)]
      (let [channel-name (message :channel_name)
            text (string/replace
                  raw-text
                  #"<(.*?)>"
                  core/replace-matches)
            username (message :user_name)
            actors (participants messages)
            highlight {:actors actors
                       :content text
                       :label (str "@"
                                   username
                                   " kicked off a conversation in #"
                                   channel-name)
                       :occurred_at (util/format-ts (or (message :ts) (message :timestamp)))
                       :category "Conversation Burst"
                       :score (util/round-to-2 (min (+ (/ (count actors) 10)
                                                       (/ (count messages) 100))
                                                    1.0))}]
        (post-highlight highlight)))))

(defn burst?
  ([key size]
   (let [start (- (now) five-minutes)
         stop (now)
         n (with-car (car/zcount key start stop))]
     (when (> n size)
       (let [last-burst (Integer. (or (last-burst-at key) 0))
             wait-time (Integer. (or (wait-for key) 0))]
         (if (< (- (now) last-burst) wait-time)
           (do (wait-for key (min (* 2 wait-time) 3600))
               (empty-bucket key start stop))
           (do (wait-for key ten-minutes)
               (last-burst-at key stop)
               (let [msgs (with-car (car/zrangebyscore key start stop))]
                 (create-highlight msgs))
               (empty-bucket key start stop)))))))
  ([key size start stop]
   (let [n (with-car (car/zcount key start stop))]
     (when (> n size)
       (with-car (car/zrangebyscore key start stop))))))

(defn- within-five-minutes [ts]
  (fn [i]
    (let [i-ts (int (read-string (i :ts)))]
      (< (Math/abs (- ts i-ts)) five-minutes))))

(defn simulate-bursts [c]
  (let [channel-name ((core/find-by-id (get-channels) c) :name)
        messages (reverse
                  (map
                   #(assoc (clojure.walk/keywordize-keys %)
                      :channel_name channel-name
                      :user_name ((core/find-by-id (get-users) (% "user")) :name))
                   (core/get-messages c)))]
    (loop [ms messages
           bucket #{}
           last-burst 0
           wait-time 0]
      (if (empty? ms)
        "Finished bursting"
        (let [m (first ms)
              ts (int (read-string (m :ts)))]
          (if (> (count bucket) 15)
            (if (< (- ts last-burst) wait-time)
              (recur (rest ms) #{} ts (min (* 2 wait-time) 3600))
              (do (create-highlight (sort-by #(int (read-string (% :ts))) bucket))
                  (recur ms #{} ts ten-minutes)))
            (recur (rest ms)
                   (conj (filter (within-five-minutes ts) bucket) m)
                   last-burst
                   wait-time)))))))
