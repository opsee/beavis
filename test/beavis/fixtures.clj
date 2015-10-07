(ns beavis.fixtures
  (:require [beavis.sql :as sql]
            [beavis.protobuilder :refer :all])
  (:import (co.opsee.proto Timestamp Target CheckResult HttpResponse Any CheckResponse)))



(defn assertion-fixtures [db]
  (sql/insert-into-assertions<! db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                    :check_id "hello"
                                    :key "header"
                                    :value "content-type"
                                    :relationship "equal"
                                    :operand "text/plain"})
  (sql/insert-into-assertions<! db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                    :check_id "hello"
                                    :key "statusCode"
                                    :relationship "equal"
                                    :operand "200"})
  (sql/insert-into-assertions<! db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                    :check_id "check2"
                                    :key "header"
                                    :value "vary"
                                    :relationship "equal"
                                    :operand "origin"})
  (sql/insert-into-assertions<! db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                    :check_id "check2"
                                    :key "statusCode"
                                    :relationship "notEqual"
                                    :operand "500"}))

(defn notification-fixtures [db]
  (sql/insert-into-notifications<! db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                       :check_id "hello"
                                       :type "email"
                                       :value "cliff@leaninto.it"})
  (sql/insert-into-notifications<! db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                       :check_id "hello"
                                       :type "slack"
                                       :value "https://slack.com/fuckoff"})
  (sql/insert-into-notifications<! db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                       :check_id "poop"
                                       :type "email"
                                       :value "poooooooooooo"}))

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
        .build)))

(defn check-result [num-responses passing-count check-index]
  "check-result will produce a CheckResult object. It prepopulates
  with a security group target that yields multiple responses. The
  result will have num-responses associated responses. The check
  index argument will allow you to create multiple successive events."
  (-> (CheckResult/newBuilder)
      (.setCheckId "check_id")
      (.setTarget (-> (Target/newBuilder)
                      (.setName "sg")
                      (.setType "sg")
                      (.setId "sg-sgsgsg")
                      .build))
      (.setTimestamp (-> (Timestamp/newBuilder)
                         (.setSeconds (* check-index 30))
                         .build))
      (.addAllResponses (conj (apply vec
                                     (map #(check-response % true) (range 0 passing-count))
                                     (map #(check-response % false) (range 0 (- num-responses passing-count))))))
      .build
      ))