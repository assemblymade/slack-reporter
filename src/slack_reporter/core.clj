(ns slack-reporter.core
  (:require [clj-http.client :as client]
            [environ.core :refer [env]]
            [opennlp.nlp :refer :all]
            [opennlp.tools.filters :refer :all]
            [opennlp.treebank :refer [make-treebank-chunker]]
            [clojure.data.json :as json]
            [clojure.string :as string]))

;; TextRank/PageRank d (by convention)
(defonce d 0.85)
(defonce special-characters
  (string/split "@ # $ % ^ & * ( ) < > : , ' <https <http" #" "))

(defonce get-sentences (make-sentence-detector "resources/models/en-sent.bin"))
(defonce tokenize (make-tokenizer "resources/models/en-token.bin"))
(defonce tag-pos (make-pos-tagger "resources/models/en-pos-maxent.bin"))

(defn now [] (quot (System/currentTimeMillis) 1000))

(defn get-start-time
  ([] (- (now) (* 24 60 60)))
  ([n] (- (now) (* n 24 60 60))))

(defn cache-results [f expiration]
  (let [cache (atom {})]
    (fn [& args]
      (if (< (or (@cache :last-called-at) Double/POSITIVE_INFINITY) expiration)
        (@cache :last-results)
        (let [results (apply f args)]
          (reset! cache {:last-called-at (now)
                         :last-results results})
          results)))))

(defn get-and-parse-body [response]
  (json/read-str (:body response)))

(defn is-file-share [message]
  (= (message "subtype") "file_share"))

(defn has-user [message]
  ((comp not nil?) (message "user")))

(defn get-file-share-comments-count [message]
  ((message "file") "comments_count"))

(defn get-file-share-name [message]
  ((message "file") "name"))

(defn get-file-share-type [message]
  ((message "file") "pretty_type"))

(defn get-file-share-user [message]
  ((message "file") "user"))

(defn get-file-share-timestamp [message]
  ((message "file") "timestamp"))

(defn get-file-share-url [message]
  ((message "file") "url"))

(defn get-user-id [user]
  (user "id"))

(defn get-user-name [user]
  (user "name"))

(defn get-user-real-name [user]
  ((user "profile") "real_name"))

(defn get-user-avatar-url [user]
  ((user "profile") "image_192"))

(defn get-channel-history [channel oldest]
  (get-and-parse-body
   (client/get (string/join ["https://slack.com/api/channels.history?token="
                             (env :slack-token)
                             "&channel="
                             channel
                             "&count=500&oldest="
                             oldest]))))

(def get-channel-history (cache-results get-channel-history (+ (now) 60)))

(defn not-deleted [user]
  (not (user "deleted")))

(defn get-users []
  (filter not-deleted
          ((get-and-parse-body
            (client/get (string/join ["https://slack.com/api/users.list?token="
                                     (env :slack-token)])))
           "members")))

(def get-users (cache-results get-users (+ (now) (* 24 60 60))))

(defn get-file-shares [channel]
  (let [history (get-channel-history channel (get-start-time))]
    (filter is-file-share (history "messages"))))

(defn get-messages [channel]
  (let [history (get-channel-history channel (get-start-time))]
    (filter has-user (history "messages"))))

(defn tag-message [message]
  (let [messages (map tokenize
                      (get-sentences (message "text")))
        m (first messages)]
    (partition-all 2 1 m)))

(def parse-file-share-message
  (juxt get-file-share-user
        get-file-share-name
        get-file-share-type
        get-file-share-url
        get-file-share-comments-count
        get-file-share-timestamp))

(def parse-user
  (juxt get-user-id
        get-user-name
        get-user-real-name
        get-user-avatar-url))

(defn transform-file-share-message [message]
  (let [[user name type url comments-count timestamp]
        (parse-file-share-message message)]
    {:user user
     :name name
     :type type
     :url url
     :comments-count comments-count
     :timestamp timestamp}))

(defn transform-user [user]
  (let [[id name real-name avatar-url] (parse-user user)]
     {:id id
      :name name
      :real-name real-name
      :avatar-url avatar-url}))

(defn find-by-id [coll id]
  (first (filter #(= (% :id) id) coll)))

(defn make-file-upload-highlight [message]
  (let [users (map transform-user (get-users))
        user (find-by-id users (message :user))
        content (str "@" (user :name)
                     " posted a " (message :type)
                     " file called <a href=\""
                     (message :url)
                     "\">"
                     (message :name)
                     "</a> that's generating a lot of buzz.")]
    {:content content
     :label "slack"
     :why (str "@"
               (user :name)
               " uploaded a file with "
               (message :comments-count)
               " comments")
     :source (message :url)}))

(defn has-comments [message]
  (> (message :comments-count) 0))

(defn highlight-file-upload [channel]
  (let [messages (filter has-comments
                         (map transform-file-share-message
                              (get-file-shares channel)))]
    (when (> (count messages) 0)
      (map make-file-upload-highlight messages))))

(defn tokenize-sentence [sentence]
  (tokenize sentence))

(defn partition-words [tokenized-sentence]
  (partition 2 1 tokenized-sentence))

(def partition-tokenized-sentences
  (comp partition-words tokenize-sentence))

(defn sentences->bigrams [message]
  (map partition-tokenized-sentences
    (get-sentences (message "text"))))

(defn messages->bigrams [messages]
  (map sentences->bigrams messages))

(defn bigram-in-message? [text]
  (fn [bigram]
    (not= (.indexOf text (string/join " " (get bigram 0))) -1)))

(defn map-sentences-to-bigrams [messages bigram-frequencies]
  (reduce #(assoc %1
             (%2 "text")
             {:ngrams (filter (bigram-in-message? (%2 "text"))
                        bigram-frequencies)
              :message %2})
          {}
          messages))

(defn remove-special-characters [bigram]
  (every? #(= (.indexOf bigram %) -1) special-characters))

(defn calculate-score [v]
  (if (= (count v) 0)
    0
    (+ (- 1 d) (* d (reduce #(+ %1 (get %2 1)) 0 v)))))

(defn process-messages [channel]
  (let [messages (get-messages channel)
        message-bigrams (messages->bigrams messages)
        sentence-bigrams (map #(apply concat %) message-bigrams)
        bigrams (filter remove-special-characters
                        (apply concat sentence-bigrams))
        bigram-frequencies (frequencies bigrams)
        messages-map (map-sentences-to-bigrams messages bigram-frequencies)
        scored-messages-map (reduce-kv #(assoc %1 %3 (calculate-score
                                                       (%3 :ngrams)))
                                                       {}
                                                       messages-map)]
    (first (reverse (sort-by val scored-messages-map)))))

(defn parse-message [message]
  (let [users (map transform-user (get-users))
        user (find-by-id users (message "user"))]
    [user (string/replace
           (message "text")
           #"<@(.*)>"
           #(str "@" ((find-by-id users (%1 1)) :name)))]))

(defn make-channel-highlight [message]
  (let [[user text] (parse-message (message :message))
         content (str "@"
                     (user :name)
                     " posted an influential chat message: \""
                     text
                     "\".")]
    {:content content
     :label "slack"
     :why (str "@"
               (user :name)
               " generated a lot of buzz")}))

(defn post-highlight [highlight]
  (when (not (nil? highlight))
    (client/post (env :titan-api-url)
                 {:basic-auth [(env :reporter-name)
                               (env :reporter-password)]
                  :body (json/write-str highlight)
                  :content-type :json})))

(defn post-file-upload-highlight [channel]
  (post-highlight (first (highlight-file-upload channel))))

(defn post-channel-highlight [channel]
  (let [[message score] (process-messages channel)]
    (post-highlight (make-channel-highlight message))))
