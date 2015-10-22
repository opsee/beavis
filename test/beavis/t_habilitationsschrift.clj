(ns beavis.t-habilitationsschrift
  (:use midje.sweet)
  (:require [beavis.fixtures :refer :all]
            [beavis.habilitationsschrift :as hab]
            [opsee.middleware.protobuilder :refer :all]
            [beavis.stream :as s]
            [riemann.config :refer [core]]
            [riemann.logging :as logging]
            [clojure.tools.logging :as log])
  (:import (clojure.lang  PersistentHashMap)))

(def received-event (atom false))
(def core-stream (atom nil))

(defn next-callback [event]
  (reset! received-event true)
  event)

(defn set-passing [result]
  (assoc result :responses (map #(assoc % :passing (= 200 (get-in % [:response :value :code]))) (:responses result))))

(logging/suppress
  ["riemann.core" "riemann.pubsub"]
  (facts
    "core services are started"
    (with-state-changes
      [(before :facts (do
                        (reset! core-stream (hab/riemann-stage))
                        (s/start-stage! @core-stream)))
       (after :facts (do
                       (s/stop-stage! @core-stream)
                       (reset! core-stream nil)
                       (reset! received-event false)))]
      (fact "pubsub service is started"
            (:pubsub @core) =not=> nil?)))

  (facts
    "everything has a customer id and check id associated with it in the index"
    (with-state-changes
      [(before :facts (do
                        (reset! core-stream (hab/riemann-stage))
                        (s/start-stage! @core-stream)))
       (after :facts (do
                       (s/stop-stage! @core-stream)
                       (reset! core-stream nil)
                       (reset! received-event false)))]
      (let [result-map (proto->hash (check-result 3 3 0))]
        (fact "result has customer id and check id"
              (let [r (s/submit @core-stream result-map next-callback)]
                (:customer_id r) =not=> nil?
                (:check_id r) =not=> nil?))
        (fact "every response has customer id and check id"
              (let [r (s/submit @core-stream result-map next-callback)]
                (every? true? (map #(and (contains? % :customer_id) (contains? % :check_id)) (:responses r))) => true)))))
  (facts
    "configuration generally works"
    (with-state-changes
      [(before :facts (do
                        (reset! core-stream (hab/riemann-stage))
                        (s/start-stage! @core-stream)))
       (after :facts (do
                       (s/stop-stage! @core-stream)
                       (reset! core-stream nil)
                       (reset! received-event false)))]
      (let [result-map (set-passing (proto->hash (check-result 3 3 0)))
            failing-result-map (set-passing (proto->hash (check-result 3 1 0)))]
        (fact "an index is created"
              (:index @core) =not=> nil?)
        (fact "submitting an event adds the result and all responses to the index"
              (s/submit @core-stream result-map next-callback)
              (count (.seq (:index @core))) => 4)
        (fact "events are only sent to the next stage if there is a state change"
              ;; Send an initial event to populate previous state -- This WILL be sent to the next
              ;; stage, so we reset the state of received-event for the subsequent calls.
              (s/submit @core-stream result-map next-callback)
              (reset! received-event false)
              ;; Send another event with the same state. This should _not_ trigger the
              ;; the callback, and so received-event should remain false.
              (s/submit @core-stream result-map next-callback)
              @received-event => false
              (s/submit @core-stream failing-result-map next-callback)
              @received-event => true)
        (fact "submit returns the correct event"
              (let [events (map #(proto->hash (check-result 3 3 %)) (range 1 4))]
                (map #(s/submit @core-stream % next-callback) events)
                (let [r (s/submit @core-stream result-map next-callback)]
                  (:time r) => (:timestamp result-map)))))
      (let [result-map (set-passing (proto->hash (check-result 3 1 0)))]
        (fact "the state of each response is set in the index"
              (count (filter #(contains? % :state) (:responses (s/submit @core-stream result-map next-callback)))) => 3)
        (fact "the state of a result with multiple failing responses is failing"
              (:state (s/submit @core-stream result-map next-callback)) => false
              (:passing (s/submit @core-stream result-map next-callback)) => false)))))
