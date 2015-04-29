(ns slack-reporter.burst-detector
  (:require [clojure.string :as string]
            [environ.core :refer [env]]
            [slack-reporter.core :as core :refer [now]]
            [taoensso.carmine :as car]))

(def redis-conn {:pool {} :spec {:uri (env :rediscloud-url)}})
(defmacro with-car [& body] `(car/wcar redis-conn ~@body))

(def five-minutes (* 5 60))
(def ten-minutes (* 2 five-minutes))

(defn add [key message]
  (with-car (car/zadd key (now) message)))

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

(defn transform-message [message]
  (let [users (map core/transform-user (core/get-users))]
    (str
     (message :user_name)
     ": "
     (string/replace
      (message :text)
      #"<@(.*)>"
      #(str "@" ((core/find-by-id users (%1 1)) :name))))))

(defn participants [msgs]
  (set (map #(str "@" (% :user_name))
            msgs)))

(defn participant-string [messages username]
  (let [p (filter #(not= (%) (str "@" username))
                  (participants messages))]
    (when (= (count p) 1)
      (first p))
    (when (= (count p) 2)
      (string/join " and "))
    (when (> (count p) 2)
      (let [r (- (count p) 2)]
        (str (string/join ", " (take 2 p))
           ", and " (if (= r 1)
                      " other"
                      " others"))))))

(defn create-highlight [messages]
  (let [message (first messages)
        channel-name (message :channel_name)
        text (message :text)
        username (message :user_name)]
    (core/post-highlight {:actors (participants messages)
                          :content (str "@"
                                        username
                                        " kicked off the conversation in #"
                                        channel-name
                                        ": "
                                        text
                                        " &mdash;&mdash; "
                                        (participant-string messages username)
                                        " joined in.")
                          :occurred_at (message :timestamp)})))

(defn burst?
  ([key size]
   (let [start (- (now) five-minutes)
         stop (now)
         n (with-car (car/zcount key start stop))]
     (when (> n size)
       (let [last-burst (Integer. (or (last-burst-at key) 0))
             wait-time (Integer. (or (wait-for key) 0))]
         (when (< (- (now) last-burst) wait-time)
           (wait-for key (* 2 wait-time))
           (empty-bucket key start stop))
         (when (> (- (now) last-burst) wait-time)
           (wait-for key ten-minutes)
           (last-burst-at key stop)
           (create-highlight (with-car
                               (car/zrangebyscore key start stop)))
           (empty-bucket key start stop))))))
  ([key size start stop]
   (let [n (with-car (car/zcount key start stop))]
     (when (> n size)
       (with-car (car/zrangebyscore key start stop))))))
