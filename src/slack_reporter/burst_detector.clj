(ns slack-reporter.burst-detector
  (:require [environ.core :refer [env]]
            [slack-reporter.core :as core :refer [now]]
            [taoensso.carmine :as car]))

(def redis-conn {:pool {} :spec {:uri (env :redistogo-url)}})
(defmacro with-car [& body] `(car/wcar redis-conn ~@body))

(def five-minutes (* 5 60))

(defn add [key message]
  (with-car (car/zadd key (now) message)))

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
                                       (map #(str (% :user_name) ": " (% :text)) (take 3 messages)))
                                      "\"")
                        :label (str "@" username " posted an influential update")
                        :why (str "\"" (core/truncate text 140) "…")})
     ;; empty the bucket so that we don't repeatedly
     ;; create burst highlights
     (with-car (doseq [message messages]
                 (car/zadd key 0 message)))))

(defn burst?
  ([key size]
   (let [start (- (now) five-minutes)
         stop (now)
         n (with-car (car/zcount key start stop))]
     (when (> n size)
       (create-highlight (with-car (car/zrangebyscore key start stop))))))
  ([key size start stop]
   (let [n (with-car (car/zcount key start stop))]
     (when (> n size)
       (with-car (car/zrangebyscore key start stop))))))
