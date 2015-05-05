(ns slack-reporter.core
  (:require [clj-http.client :as client]
            [clj-time.core :as t]
            [environ.core :refer [env]]
            [opennlp.nlp :refer :all]
            [opennlp.tools.filters :refer :all]
            [slack-reporter.replay :as replay]
            [slack-reporter.reporter :refer [post-highlight]]
            [slack-reporter.util :as util :refer [now]]
            [clojure.data.json :as json]
            [clojure.string :as string]))

;; TextRank/PageRank d (by convention)
(defonce d 0.85)
(defonce special-characters
  (string/split "@ # $ % ^ & * ( ) < > : , ' <https <http" #" "))

(defonce get-sentences (make-sentence-detector "resources/models/en-sent.bin"))
(defonce tokenize (make-tokenizer "resources/models/en-token.bin"))
(defonce tag-pos (make-pos-tagger "resources/models/en-pos-maxent.bin"))

(def slackbot {"id" "USLACKBOT"
               :id "USLACKBOT"
               "name" "slackbot"
               :name "slackbot"
               :user_name "slackbot"
               "avatar_url" "https://slack-assets2.s3-us-west-2.amazonaws.com/
                            10068/img/slackbot_192.png"
               :avatar-url "https://slack-assets2.s3-us-west-2.amazonaws.com/
                            10068/img/slackbot_192.png"
               :avatar_url "https://slack-assets2.s3-us-west-2.amazonaws.com/
                            10068/img/slackbot_192.png"
               "profile" {"real_name" "Slackbot"}
               :profile {:real-name "Slackbot"
                         :real_name "Slackbot"}})

(defn get-start-time
  ([] (- (now) (* 24 60 60)))
  ([n] (- (now) (* n 24 60 60))))

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
   (client/get (str "https://slack.com/api/channels.history?token="
                    (env :slack-token)
                    "&channel="
                    channel
                    "&count=500&oldest="
                    oldest))))

(def get-channel-history (util/cache-results get-channel-history (+ (now) 60)))

(defn not-archived [c]
  (not (c "is_archived")))

(defn not-deleted [user]
  (not (user "deleted")))

(defn get-users []
  (conj (filter not-deleted
           ((get-and-parse-body
             (client/get (str "https://slack.com/api/users.list?token="
                              (env :slack-token))))
            "members")) slackbot))

(def get-users (util/cache-results get-users (+ (now) (* 24 60 60))))

(defn get-channels []
  (filter not-archived
          ((get-and-parse-body
            (client/get (str "https://slack.com/api/channels.list?token="
                             (env :slack-token))))
           "channels")))

(def get-channels (util/cache-results get-channels (+ (now) (* 60 60))))

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

(defn transform-channel [c]
  (clojure.walk/keywordize-keys c))

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
        comments-count (message :comments-count)
        label (str "@" (user :name)
                     " uploaded a " (message :type)
                     " file")]
    {:content (message :name)
     :label label
     :occurred_at (util/format-ts (message :timestamp))
     :source (message :url)
     :category "File Upload"
     :score (util/round-to-2 (min (* (/ comments-count 100) 10) 1.0))}))

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
    (sort-by val > scored-messages-map)))

(defn replace-s [prefix f s]
  (if (> (.indexOf s "|") -1)
    (str prefix (subs s (+ (.indexOf s "|") 1)))
    (let [m (f)]
      (str prefix ((find-by-id m s) :name)))))

(defn replace-capture [s]
  (if (> (.indexOf s "|") -1)
    (str (subs s (+ (.indexOf s "|") 1)))
    s))

(defn replace-channel [s]
  (replace-s "#" #(map transform-channel (get-channels)) s))

(defn replace-command [s]
  ;; we don't have a lookup function for commands,
  ;; so (even though it's less than ideal)
  ;; we'll just make the lookup function return
  ;; <{s}> (which is what Slack says to do).
  (replace-s "" #({:name (str "<" s ">")}) s))

(defn replace-user [s]
  (replace-s "@" #(map transform-user (get-users)) s))

;; https://api.slack.com/docs/formatting
(defn replace-matches [[full-match capture]]
  (cond
   (= (.indexOf capture "@U") 0) (replace-user (subs capture 1))
   (= (.indexOf capture "#C") 0) (replace-channel (subs capture 1))
   (= (.indexOf capture "!") 0) (replace-command (subs capture 1))
   :else (replace-capture capture)))

(defn parse-message [message]
  (let [users (map transform-user (get-users))
        user (find-by-id users (message "user"))]
    [user (string/replace
           (message "text")
           #"<(.*?)>"
           replace-matches)]))

(defn make-channel-highlight [message score]
  (let [[user text] (parse-message (message :message))
         label (str "@" (user :name) " said something important")]
    {:actors [(user :name)]
     :content text
     :label label
     :occurred_at (util/format-ts (get-in message [:message "ts"]))
     :category "Important Comment"
     :score (util/round-to-2 (min (/ score 100) 1.0))}))

(defn post-file-upload-highlight [channel]
  (post-highlight (first (highlight-file-upload channel))))

(defn post-channel-highlight [channel]
  (let [[message score] (first (process-messages channel))
        highlight (make-channel-highlight message score)]
    (replay/add "assembly" highlight)
    (post-highlight highlight)))

(defn post-channel-highlights
  "Posts the top {n} highlights from channel {c}."
  [c n]
  (let [msg-score-pairs (take n (process-messages c))
         highlights (map #(apply make-channel-highlight %) msg-score-pairs)]
    (map post-highlight highlights)))

;; TODO: Move these functions (which are just
;; for refreshing the data) to their own namespace

(defn- important-comments
  ([c]
   (post-channel-highlights c 3))
  ([c n]
   (post-channel-highlights c n)))

(defn- refresh []
  (client/delete (str (env :titan-api-url) "/reporter")
                 {:basic-auth [(env :reporter-name)
                               (env :reporter-password)]})
  (important-comments (env :target-channel)))
