(ns slack-reporter.core-test
  (:require [clojure.test :refer :all]
            [slack-reporter.core :refer :all]))

(def test-channel
  {"id" "CDEF456"
   "name" "mychannel"})

(def test-user
  {"id" "UABC123"
   "name" "lmarvin"
   "profile" {"real_name" "Lee Marvin"
              "image_192" "http://avatarzzz.com/lmarvin"}})

(defn mock-get-channels []
  [test-channel])

(defn mock-get-users []
  [test-user])

(deftest test-replace-channel
  (with-redefs [slack-reporter.core/get-channels mock-get-channels]
    (is (= (replace-channel "CDEF456|not_mychannel") "#not_mychannel"))
    (is (= (replace-channel "CDEF456") "#mychannel"))))

(deftest test-replace-user
  (with-redefs [slack-reporter.core/get-users mock-get-users]
    (is (= (replace-user "UABC123|not_lmarvin") "@not_lmarvin"))
    (is (= (replace-user "UABC123") "@lmarvin"))))

(deftest test-parse-message
  (let [message {"user" "UABC123"
                 "text" "Hey, <@UABC123> in <#CDEF456>"}
        user (slack-reporter.core/transform-user test-user)]
    (with-redefs [slack-reporter.core/get-users mock-get-users
                  slack-reporter.core/get-channels mock-get-channels]
      (is (= (parse-message message) [user "Hey, @lmarvin in #mychannel"])))))
