(ns beavis.t-habilitationsschrift
  (:use midje.sweet)
  (:require [beavis.fixtures :refer :all]
            [beavis.habilitationsschrift :as hab]
            [beavis.protobuilder :refer :all]
            [beavis.stream :as s]
            [riemann.config :refer [core]]
            [riemann.logging :as logging]
            [clojure.tools.logging :as log])
  (:import (co.opsee.proto CheckResult CheckResponse Target Timestamp Any HttpResponse)
           (beavis.stream StreamStage)
           (clojure.lang PersistentArrayMap)))

(def received-event (atom false))
(def core-stream (atom nil))

(defn next-callback [event]
  (reset! received-event true)
  event)
(set-format "Timestamp" "int64")

(logging/suppress
  ["riemann.core" "riemann.pubsub"]
  (facts
    "core services are started"
    (with-state-changes
      [(before :facts (do
                        (reset! core-stream (hab/CoreStreamer 1))
                        (s/start-stage! @core-stream)))
       (after :facts (do
                       (s/stop-stage! @core-stream)
                       (reset! core-stream nil)
                       (reset! received-event false)))]
      (fact "pubsub service is started"
            (:pubsub @core) =not=> nil?)))

  (facts
    "configuration generally works"
    (with-state-changes
      [(before :facts (do
                        (reset! core-stream (hab/CoreStreamer 1))
                        (s/start-stage! @core-stream)))
       (after :facts (do
                       (s/stop-stage! @core-stream)
                       (reset! core-stream nil)
                       (reset! received-event false)))]
      (let [result-map (proto->hash (check-result 3 3 0))]
        (fact "an index is created"
              (:index @core) =not=> nil?)
        (fact "submitting an event adds it to the index"
              (s/submit @core-stream result-map next-callback)
              (count (.seq (:index @core))) => 1)
        (fact "submitting an event sends it to the next step"
              (s/submit @core-stream result-map next-callback)
              @received-event => true)
        (fact "submit returns the correct event"
              (let [events (map #(proto->hash (check-result 3 3 %)) (range 1 4))]
                (map #(s/submit @core-stream % next-callback) events)
                (let [r (s/submit @core-stream result-map next-callback)]
                  (type r) => PersistentArrayMap
                  (:time r) => (:timestamp result-map))))))))