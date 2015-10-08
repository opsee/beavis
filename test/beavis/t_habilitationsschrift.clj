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
           (beavis.stream StreamStage)))

(defn next-callback [_] nil)
(set-format "Timestamp" "int64")

(logging/suppress
  ["riemann.core" "riemann.pubsub"]
  (facts
    "core services are started"
    (against-background
      [(around :facts
               (let [core-stream (hab/CoreStreamer 1)]
                 (s/start-stage! core-stream)
                 ?form
                 (s/stop-stage! core-stream)))]
      (fact "pubsub service is started"
            (:pubsub @core) =not=> nil?)))
  (facts
    "configuration works as expected"
    (let [result-map (proto->hash (check-result 3 3 0))]
      (against-background
        [(around :facts
                 (let [core-stream (hab/CoreStreamer 1)]
                   (s/start-stage! core-stream)
                   ?form
                   (s/stop-stage! core-stream)))]
        (fact "an index is created"
              (:index @core) =not=> nil?)
        (fact "submitting an event adds it to the index"
              (s/submit core-stream result-map next-callback)
              (Thread/sleep 1000)
              (count (.seq (:index @core))) => 1)))))