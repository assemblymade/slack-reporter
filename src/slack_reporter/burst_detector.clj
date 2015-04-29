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

(defn create-highlight [messages]
  (let [message (first messages)
        channel-name (message :channel_name)
        text (message :text)
        username (message :user_name)]
    (core/post-highlight {:content (str "@"
                                        username
                                        " kicked off the conversation in #"
                                        channel-name
                                        "\n\n"
                                        (string/join
                                         "\n-"
                                         (map transform-message (take 3 messages))))
                          :event_happened_at (message :timestamp)})))

(defn burst?
  ([key size]
   (let [start (- (now) five-minutes)
         stop (now)
         n (with-car (car/zcount key start stop))]
     (when (> n size)
       (println n)
       (let [last-burst (Integer. (or (last-burst-at key) 0))
             wait-time (Integer. (or (wait-for key) 0))]
         (last-burst-at key stop)
         (when (< (- (now) last-burst) wait-time)
           (wait-for key (* 2 wait-time))
           (empty-bucket key start stop))
         (when (> (- (now) last-burst) wait-time)
           (wait-for key ten-minutes)
           (create-highlight (with-car
                               (car/zrangebyscore key start stop)))
           (empty-bucket key start stop))))))
  ([key size start stop]
   (let [n (with-car (car/zcount key start stop))]
     (when (> n size)
       (with-car (car/zrangebyscore key start stop))))))
