(ns slack-reporter.db.config
  (:import [java.net URI])
  (:require [clj-time.coerce :as coerce]
            [clj-time.format :as format]
            [clojure.data.json :as json]
            [clojure.java.jdbc :refer [db-do-commands]]
            [clojure.set :as set]
            [clojure.string :as string]
            [environ.core :refer [env]])
  (:use [korma.core]
        [korma.db]))

(defn- parse-uri [u]
  (let [url (new URI u)
        user-pass (.getUserInfo url)]
    {:host (.getHost url)
     :port (.getPort url)
     :db (subs (.getPath url) 1)
     :user (if user-pass
             ((string/split user-pass #":") 0))
     :password (if user-pass
                 (get (string/split user-pass #":") 1))}))

(defn get-datasource [u]
  (let [{:keys [host port db user password]} (parse-uri u)]
    (doto (org.postgresql.ds.PGSimpleDataSource.)
      (.setServerName host)
      (.setPortNumber port)
      (.setDatabaseName db)
      (.setUser user)
      (.setPassword password))))

(def pg (postgres (parse-uri (env :database-url))))

(defdb db pg)

(defentity webhooks)

(defentity messages
  (prepare
   (fn [v]
     (set/rename-keys
      (assoc v :message (json/write-str (v :message)))
      (reduce
       #(assoc %1
          (first %2)
          (keyword (string/replace (name (first %2)) #"-" "_")))
       {}
       v))))
  (transform
   (fn [v]
     (set/rename-keys
      (assoc v :message (json/read-str (v :message))) (reduce
         #(assoc %1
            (first %2)
            (keyword (string/replace (name (first %2)) #"_" "-")))
         {}
         v)))))
