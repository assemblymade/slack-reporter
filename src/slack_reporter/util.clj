(ns slack-reporter.util
  (:require [clj-time.coerce :as c]))

(defn now [] (quot (System/currentTimeMillis) 1000))

(defn cache-results [f expiration]
  (let [cache (atom {})]
    (fn [& args]
      (if (< (or (@cache :last-called-at) Double/POSITIVE_INFINITY) expiration)
        (@cache :last-results)
        (let [results (apply f args)]
          (reset! cache {:last-called-at (now)
                         :last-results results})
          results)))))

(defn format-ts [ts]
  (str (c/to-sql-time (* 1000 (int (read-string ts))))))

(defn truncate [s n]
  (subs s 0 (min (count s) n)))

(defn round-to [p d]
  (let [factor (Math/pow 10 p)]
    (/ (Math/round (* d factor)) factor)))

(def round-to-2 (partial round-to 2))
