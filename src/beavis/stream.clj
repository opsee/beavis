(ns beavis.stream
  (:require [clojure.tools.logging :as log])
  (:import (co.opsee.proto CheckResult)
           (java.util.concurrent ForkJoinPool TimeUnit ForkJoinTask)
           (java.util.concurrent.atomic AtomicLongArray)))

(defprotocol StreamProducer
  "The StreamProducer gets messages from the outside world and submits them into the pipeline.
  There can only be one StreamProducer per pipeline."
  (start-producer! [this next]
    "This method starts any connections needed for submitting work into the pipeline.
    Next is an fn that takes a single parameter, a mapified version of a CheckResult.
    Start-producer! is called from the main thread and is expected not to block.")
  (stop-producer! [this]
    "Cleans up any resources used by this producer. Must not return until the producer
    is completely shut down and drained, ie next will not be called again."))

(defprotocol StreamStage
  "StreamStages perform transformations on CheckResults. Anything wishing to operate on
  CheckResults should implement StreamStage. StreamStages are then composed together
  via the pipeline function."
  (start-stage! [this]
    "Start method for any resources this stage may manage.")
  (stop-stage! [this]
    "Stop method for any resources this stage may manage. The pipeline guarantees that no
    other calls to submit will be active when stop-stage! gets called.")
  (submit [this work next]
    "Submit implements the work that this stream stage is meant to perform. Generally, submit
    should not block and you cannot make predictions about which thread will be calling.
    Submit must also be threadsafe. Next is an fn which takes the transformed CheckResult-map
    and submits it to the next stage of the stream. If the stream stage is terminal, next
    needn't be called, however it will not fail regardless."))

(defn task [work stage counts index total next-callback]
  (proxy [ForkJoinTask] []
    (exec []
      (try
        (submit stage work next-callback)
        (finally (when (= index (dec total))
                   (swap! counts dec)))))))

(defn make-callback [^ForkJoinPool pool stage counts next-index total next-callback]
  (fn [work]
    (when (= 1 next-index)
      (swap! counts inc))
    (if next-callback
      (let [t (task work stage counts next-index total next-callback)]
        (.execute pool t)))))

(defn- pipeline-callbacks [^ForkJoinPool pool stages counts]
  (let [total (count stages)]
    (loop [index (count stages)
           stage nil
           stages (reverse stages)
           callbacks nil]
      (let [callbacks' (cons
                         (make-callback pool stage counts index total (first callbacks))
                         callbacks)]
        (if (not-empty stages)
          (recur (- index 1)
                 (first stages)
                 (rest stages)
                 callbacks')
          callbacks)))))

(defn- wait-for-drain [counts]
  (loop []
    (when (not= 0 @counts)
      (Thread/sleep 1000)
      (recur))))

(defprotocol Pipeline
  (start-pipeline! [this])
  (stop-pipeline! [this]))

(defn pipeline [producer & stages]
  (let [pool (ForkJoinPool.)
        count (atom 0)
        callbacks (pipeline-callbacks pool (cons producer stages) count)]
    (reify Pipeline
      (start-pipeline! [_]
        (doseq [stage stages]
          (start-stage! stage))
        (start-producer! producer (first callbacks)))
      (stop-pipeline! [_]
        ;first stop the producer which will wait until it's drained
        (stop-producer! producer)
        (let [idv (map vector (iterate inc 0) stages)]
          (wait-for-drain count)
          (doseq [[index stage] idv]
            (stop-stage! stage)))
        ;then tell the fjp to stop accepting tasks
        (.shutdown pool)))))