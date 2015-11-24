(ns beavis.fixtures
  (:require [beavis.sql :as sql]
            [opsee.middleware.protobuilder :refer :all]
            [riemann.index :as index]
            [riemann.config :refer [core]])
  (:import (co.opsee.proto Timestamp Target CheckResult HttpResponse Any CheckResponse)))



(defn assertion-fixtures [db]
  (sql/insert-into-assertions<! db {:customer_id  "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                    :check_id     "hello"
                                    :key          "header"
                                    :value        "content-type"
                                    :relationship "equal"
                                    :operand      "text/plain"})
  (sql/insert-into-assertions<! db {:customer_id  "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                    :check_id     "hello"
                                    :key          "code"
                                    :relationship "equal"
                                    :operand      "200"})
  (sql/insert-into-assertions<! db {:customer_id  "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                    :check_id     "check2"
                                    :key          "body"
                                    :relationship "contain"
                                    :operand      "OK"})
  (sql/insert-into-assertions<! db {:customer_id  "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                    :check_id     "check2"
                                    :key          "code"
                                    :relationship "notEqual"
                                    :operand      "500"})
  (sql/insert-into-assertions<! db {:customer_id  "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                    :check_id     "goodcheck"
                                    :key          "code"
                                    :relationship "equal"
                                    :operand      "200"})
  (sql/insert-into-assertions<! db {:customer_id  "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                    :check_id     "check1"
                                    :key          "body"
                                    :relationship "notEmpty"}))

(defn notification-fixtures [db]
  (sql/insert-into-notifications<! db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                       :check_id    "hello"
                                       :type        "email"
                                       :value       "cliff@leaninto.it"})
  (sql/insert-into-notifications<! db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                       :check_id    "hello"
                                       :type        "slack"
                                       :value       "https://slack.com/fuckoff"})
  (sql/insert-into-notifications<! db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                       :check_id    "poop"
                                       :type        "email"
                                       :value       "poooooooooooo"})
  (sql/insert-into-notifications<! db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce2"
                                       :check_id    "1"
                                       :type        "email"
                                       :value       "greg@opsee.com"})
  (sql/insert-into-notifications<! db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                       :check_id    "check1"
                                       :type        "email"
                                       :value       "cliff@leaninto.it"}))

(defn alert-fixtures [db]
  (sql/create-alert! db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                         :check_id    "hello"
                         :check_name  "hello"})
  (sql/create-alert! db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                         :check_id    "poop"
                         :check_name  "poop"})
  (sql/create-alert! db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                         :check_id    "check1"
                         :check_name  "doop"})
  (let [alert (first (sql/get-latest-alert db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                               :check_id    "poop"}))]
    (sql/resolve-alert! db {:alert_id (:id alert)})))

(defn passing-response ^Any []
  (-> (Any/newBuilder)
      (.setTypeUrl "HttpResponse")
      (.setValue (-> (HttpResponse/newBuilder)
                     (.setBody "OK")
                     (.setHost "hostname")
                     (.setCode 200)
                     .build
                     .toByteString))
      .build))

(defn failing-response ^Any []
  (-> (Any/newBuilder)
      (.setTypeUrl "HttpResponse")
      (.setValue (-> (HttpResponse/newBuilder)
                     (.setBody "NOT OK")
                     (.setHost "hostname")
                     (.setCode 404)
                     .build
                     .toByteString))
      .build))

(defn check-response [index passing]
  (let [address (str "192.168.0." index)
        name (str "instance" index)
        id (str "i-" (apply str (repeat 8 index)))]
    (-> (CheckResponse/newBuilder)
        (.setTarget (-> (Target/newBuilder)
                        (.setAddress address)
                        (.setName name)
                        (.setId id)
                        (.setType "instance")
                        .build))
        (.setResponse (if passing (passing-response) (failing-response)))
        (.setPassing passing)
        .build)))

(defn reset-index []
  (index/clear (:index @core)))

(defn check-result
  "check-result will produce a CheckResult object. It prepopulates
  with a security group target that yields multiple responses. The
  result will have num-responses associated responses. The check
  index argument will allow you to create multiple successive events."
  ([num-responses passing-count check-index]
   (check-result "check_id" num-responses passing-count check-index))
  ([check-id num-responses passing-count check-index]
   (check-result "customer" check-id num-responses passing-count check-index))
  ([customer-id check-id num-responses passing-count check-index]
   (-> (CheckResult/newBuilder)
       (.setCustomerId customer-id)
       (.setCheckId check-id)
       (.setCheckName check-id)
       (.setTarget (-> (Target/newBuilder)
                       (.setName "sg")
                       (.setType "sg")
                       (.setId "sg-sgsgsg")
                       .build))
       (.setTimestamp (-> (Timestamp/newBuilder)
                          (.setSeconds (+ (/ (System/currentTimeMillis) 1000) (* check-index 30)))
                          .build))
       (.addAllResponses (concat
                           (map #(check-response % true) (range 0 passing-count))
                           (map #(check-response % false) (range passing-count num-responses))))
       .build)))
