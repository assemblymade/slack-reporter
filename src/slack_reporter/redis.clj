(ns slack-reporter.redis
  (:require [environ.core :refer [env]]
            [taoensso.carmine :as car]))

(def redis-conn {:pool {} :spec {:uri (env :rediscloud-url)}})
(defmacro with-car [& body] `(car/wcar redis-conn ~@body))
