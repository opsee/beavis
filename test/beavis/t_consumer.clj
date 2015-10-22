(ns beavis.t-consumer
  (:use midje.sweet)
  (:require [beavis.consumer :as c]
            [opsee.middleware.protobuilder :as proto]
            [clojure.tools.logging :as log])
  (:import (com.github.brainlag.nsq NSQMessage Connection)
           (java.util Date)
           (co.opsee.proto CheckResult)))

(def received-message (atom nil))
(def message-finished (atom false))

(defn- nsq-message-stub []
  (proxy [NSQMessage] []
    (finished []
      (reset! message-finished true)
      nil)
    (requeue
      ([_] nil)
      ([] nil))))

(defn- check-result []
  (-> (CheckResult/newBuilder)
      (.setCheckId "0")
      (.setCustomerId "hi")
      .build))

(defn- nsq-message []
  (let [msg (nsq-message-stub)]
    (.setId msg (byte-array [0]))
    (.setAttempts msg 0)
    (.setTimestamp msg (Date.))
    (.setMessage msg (.toByteArray (check-result)))
    msg))

(defn- next-step [obj]
  (reset! received-message obj))

(facts "consumes messages and emits maps"
       (with-state-changes
         [(before :facts (do
                           (reset! received-message nil)
                           (reset! message-finished false)))]
         (let [msg (nsq-message)]
           (fact "handler finishes received message"
                 (.message (c/handle-message next-step) msg)
                 @message-finished => true
           (fact "handler translates protobuf object to map"
                 (.message (c/handle-message next-step) msg)
                 (:customer_id @received-message) => "hi")))))