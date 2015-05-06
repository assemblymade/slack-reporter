(ns slack-reporter.keywords
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [environ.core :refer [env]]
            [slack-reporter.core :as core :refer [d]]))


(def overlap
  ;; overlap takes a set of two sets
  ;; and memoizes their intersection
  (memoize (fn [s]
             (clojure.set/intersection (first s) (second s)))))

(defn score [ws]
  (fn [s]
    (+ (- 1 d)
       (* d (reduce #(+ %1 (/ (count (overlap #{s %2}))
                              (+ (Math/log (count s))
                                 (Math/log (count %2)))))
                    0
                    (disj ws s))))))

(defn rank [text]
  (let [msgs (core/get-sentences text)
        sets (map set msgs)
        scorer (score (set sets))
        scored (reduce #(assoc %1 %2 (scorer (set %2)))
                       {}
                       msgs)
        sorted (sort-by val > scored)]
   sorted))

(defn get-freq [w]
  (or (try (get-in
            (json/read-str
             (:body
              (client/get (string/replace (env :word-api-url) "{word}" w)
                          {:headers {"X-Mashape-Key" (env :mashape-key)
                                     "Accept" "application/json"}})))
            ["frequency" "perMillion"])
           (catch Exception e 100.00))
      100.00))

(def get-freq (memoize get-freq))

(defn keywords [t]
  (let [ranks (rank t)
        words (flatten (map core/tokenize (core/get-sentences t)))
        freq (frequencies (map string/lower-case words))
        total (reduce #(+ %1 (val %2)) 0 freq)
        word-freqs (reduce-kv #(assoc %1 %2 {:local-frequency (double (/ %3 total))
                                             :global-frequency (get-freq %2)})
                              {}
                              freq)
        scored-words (reduce-kv #(assoc %1 %2 (/ (%3 :local-frequency)
                                                 (or (%3 :global-frequency) 100)))
                                {}
                                word-freqs)]
    (sort-by val > scored-words)))

(defn extract []
  (let [sorted-msgs (map
                     #(vec [(get % 1) (get-in % [0 :message "text"])])
                     (core/process-messages "C0250R8DP"))
        joined-msgs (string/join ". " (take 100 sorted-msgs))]
    sorted-msgs))
