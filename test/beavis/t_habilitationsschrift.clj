(ns beavis.t-habilitationsschrift
  (:use midje.sweet)
  (:require [beavis.fixtures :refer :all]
            [beavis.habilitationsschrift :as hab]
            [opsee.middleware.protobuilder :refer :all]
            [beavis.stream :as s]
            [riemann.config :refer [core]]
            [riemann.logging :as logging]
            [clojure.tools.logging :as log])
  (:import (clojure.lang PersistentHashMap)))

(def received-count (atom 0))
(def core-stream (atom nil))

(defn next-callback [event]
  (swap! received-count inc)
  event)



(facts
  "core services are started"
  (with-state-changes
    [(before :facts (do
                      (reset! core-stream (hab/riemann-stage))
                      (s/start-stage! @core-stream)))
     (after :facts (do
                     (s/stop-stage! @core-stream)
                     (reset! core-stream nil)
                     (reset! received-count 0)))]
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
                     (reset! received-count 0)))]
    (let [result-map (check-result 3 3 0)]
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
                      (s/start-stage! @core-stream)
                      (reset-index)))
     (after :facts (do
                     (s/stop-stage! @core-stream)
                     (reset! core-stream nil)
                     (reset! received-count 0)
                     (reset! core nil)))]
    (fact "old events are ignored"
          (let [e1 (check-result "customer" "check" 3 3 0)
                e2 (check-result "customer" "check" 3 3 1)]
            (get-in (s/submit @core-stream e2 next-callback) [:timestamp :seconds]) => (:time e2)
            (get-in (s/submit @core-stream e1 next-callback) [:timestamp :seconds]) => (:time e2)))
    ;; i am so fucking bad and lazy. -greg
    (let [p1 (check-result 3 3 0)
          result-map p1
          p2 (check-result 3 3 1)
          f1 (check-result 3 1 2)
          f2 (check-result 3 1 3)
          f3 (check-result 3 1 4)
          f4 (check-result 3 1 5)
          f5 (check-result 3 1 6)]
      (fact "an index is created"
            (:index @core) =not=> nil?)
      (fact "submitting an event adds the result and all responses to the index"
            (s/submit @core-stream result-map next-callback)
            (count (.seq (:index @core))) => 4)
      (fact "events are only sent to the next stage if there is a stable state change for 90 seconds"
            ;; The buffer in riemann.streams/stable is empty before this, so it should initialize the state
            ;; of the stable function and not pass the event on.
            (s/submit @core-stream p1 next-callback)
            @received-count => 0
            ;; dt has not passed, so we should still be buffering
            (s/submit @core-stream p2 next-callback)
            @received-count => 0
            ;; Now the states differ, so we should clear the buffer and start again
            (s/submit @core-stream f1 next-callback)
            @received-count => 0
            (s/submit @core-stream f2 next-callback)
            @received-count => 0
            (s/submit @core-stream f3 next-callback)
            @received-count => 0
            (s/submit @core-stream f4 next-callback)
            ;; riemann waits until _after_ dt has elapsed so on the _next_ call, it will pass everything through.
            @received-count => 4)
      (fact "submit returns the correct event"
            (let [events (map #(check-result 3 3 %) (range 1 4))]
              (map #(s/submit @core-stream % next-callback) events)
              (let [r (s/submit @core-stream result-map next-callback)]
                (:time r) => (.getSeconds (.getTimestamp result-map))))))
    (let [result-map (check-result 3 1 0)]
      (fact "the state of each response is set in the index"
            (count (filter #(contains? % :state) (:responses (s/submit @core-stream result-map next-callback)))) => 3)
      (fact "the state of a result with multiple failing responses is failing"
            (:state (s/submit @core-stream result-map next-callback)) => false
            (:passing (s/submit @core-stream result-map next-callback)) => false))))
