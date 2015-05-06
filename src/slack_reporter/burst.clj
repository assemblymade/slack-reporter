(ns slack-reporter.burst
  (:require [clojure.string :as string]
            [environ.core :refer [env]]
            [slack-reporter.core :as core]
            [slack-reporter.redis :refer [with-car]]
            [slack-reporter.replay :as replay]
            [slack-reporter.reporter :refer [post-highlight]]
            [slack-reporter.util :as util :refer [now]]
            [taoensso.carmine :as car]))

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
                       :occurred_at (util/format-ts (message :ts))
                       :category "Conversation Burst"
                       :score (util/round-to-2 (min (/ (count messages) 100)))}]
        (post-highlight highlight)))))

(defn burst?
  ([key size]
   (let [start (- (now) five-minutes)
         stop (now)
         n (with-car (car/zcount key start stop))]
     (when (> n size)
       (let [last-burst (Integer. (or (last-burst-at key) 0))
             wait-time (Integer. (or (wait-for key) 0))]
         (when (< (- (now) last-burst) wait-time)
           (wait-for key (max (* 2 wait-time) 3600))
           (empty-bucket key start stop))
         (when (> (- (now) last-burst) wait-time)
           (wait-for key ten-minutes)
           (last-burst-at key stop)
           (let [msgs (with-car (car/zrangebyscore key start stop))]
             (replay/add "burst" msgs)
             (create-highlight msgs))
           (empty-bucket key start stop))))))
  ([key size start stop]
   (let [n (with-car (car/zcount key start stop))]
     (when (> n size)
       (with-car (car/zrangebyscore key start stop))))))

(defn- fake-burst [c]
  (let [users (get-users)
        messages (map #(assoc (clojure.walk/keywordize-keys %)
                         :channel_name "important"
                         :user_name ((core/find-by-id users (% "user")) :name))
                      (core/get-messages c))]
    (replay/add "fake-burst" messages)
    (create-highlight messages)))

(defn replay-bursts [k]
  (let [msgs (flatten (replay/fetch k))]
    (when (> (count msgs) 0)
      (create-highlight msgs))))

(defn- within-ten-minutes [ts]
  (fn [i]
    (let [i-ts (int (read-string (i :ts)))]
      (< (Math/abs (- ts i-ts)) ten-minutes))))

(defn simulate-bursts [c]
  (let [messages (vec (map #(assoc (clojure.walk/keywordize-keys %)
                              :channel_name "important"
                              :user_name ((core/find-by-id (get-users) (% "user")) :name))
                           (core/get-messages c)))]
    (loop [ms messages
           bucket []]
      (if (empty? ms)
        "Finished bursting"
        (let [m (first ms)
              ts (int (read-string (m :ts)))]
          (if (> (count bucket) 12)
            (do (create-highlight (sort-by #(int (read-string (% :ts))) bucket))
                (recur ms []))
            (recur (rest ms)
                   (conj (vec (filter (within-ten-minutes ts) bucket)) m))))))))
