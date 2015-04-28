(ns slack-reporter.burst-detector
  (:require [environ.core :refer [env]]
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

(defn create-highlight [messages]
  (let [message (first messages)
        channel-name (message :channel_name)
        text (message :text)
        username (message :user_name)]
    (core/post-highlight {:content (str "@"
                                        username
                                        " posted an update that generated a lot of buzz in #"
                                        channel-name
                                        ": \""
                                        (clojure.string/join
                                         "\n"
                                         (map #(str
                                                (% :user_name)
                                                ": "
                                                (% :text))
                                              (take 3 messages)))
                                        "\"")
                          :label (str "@" username " got the conversation rolling!")
                          :why (str "\"" (core/truncate text 140) "…")})))

(defn burst?
  ([key size]
   (let [start (- (now) five-minutes)
         stop (now)
         n (with-car (car/zcount key start stop))]
     (when (> n size)
       (empty-bucket key start stop)
       (let [last-burst (Integer. (or (last-burst-at key) 0))
             wait-time (Integer. (or (wait-for key) 0))]
         (last-burst-at key stop)
         (when (< (- (now) last-burst) five-minutes)
           (wait-for key (* 2 wait-time)))
         (when (> (- (now) last-burst) wait-time)
           (wait-for key ten-minutes)
           (create-highlight (with-car
                               (car/zrangebyscore key start stop))))))))
  ([key size start stop]
   (let [n (with-car (car/zcount key start stop))]
     (when (> n size)
       (with-car (car/zrangebyscore key start stop))))))
