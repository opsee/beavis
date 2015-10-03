(ns beavis.t-stream
  (:use midje.sweet
        opsee.middleware.test-helpers)
  (:require [beavis.stream :as stream]
            [clojure.tools.logging :as log]
            [opsee.middleware.identifiers :refer [generate-id]])
  (:import (beavis.stream StreamProducer StreamStage)
           (co.opsee.proto CheckResult)
           (java.util.concurrent ScheduledThreadPoolExecutor TimeUnit ScheduledFuture)))

(defn- work []
  (-> (CheckResult/newBuilder)
      (.setCheckId (generate-id))
      (.setCustomerId "hi")
      .build))

(defn producer [count number]
  (let [pool (ScheduledThreadPoolExecutor. 5)]
    (reify StreamProducer
      (start-producer! [_ next]
        (.scheduleAtFixedRate pool (reify Runnable
                                     (run [_]
                                       (when (< @count number)
                                         (swap! count inc)
                                         (next (work)))))
                              100 100 TimeUnit/MILLISECONDS))
      (stop-producer! [_]
        (.shutdown pool)
        (loop []
          (when-not (.awaitTermination pool 1000 TimeUnit/MILLISECONDS)
            (recur)))))))

(defn order-stage [order i]
  (reify StreamStage
    (submit [_ work next]
      (swap! order conj i)
      (next work))))

(defn count-stage [count]
  (reify StreamStage
    (submit [_ work next]
      (swap! count inc)
      (next work))))

(defn terminal-stage []
  (reify StreamStage
    (submit [_ work next]
      )))

(defn modify-and-store-stage [storage cust-id]
  (reify StreamStage
    (submit [_ work next]
      (reset! storage work)
      (next (-> (.toBuilder work)
                (.setCustomerId cust-id)
                (.build))))))

(facts "pipelines basic logic"
  (fact "executes in order"
    (let [order (atom ())
          count (atom 0)
          pipe (stream/pipeline (producer count 1)
                                (order-stage order 1)
                                (order-stage order 2)
                                (order-stage order 3))]
      (stream/start-pipeline! pipe)
      (Thread/sleep 500)
      (stream/stop-pipeline! pipe)
      @order => (just [3 2 1])))
  (fact "waits for drain"
    (let [producer-count (atom 0)
          one-count (atom 0)
          two-count (atom 0)
          three-count (atom 0)
          pipe (stream/pipeline (producer producer-count 40)
                                (count-stage one-count)
                                (count-stage two-count)
                                (count-stage three-count))]
      (stream/start-pipeline! pipe)
      (Thread/sleep 700)
      (stream/stop-pipeline! pipe)
      @one-count => @producer-count
      @two-count => @producer-count
      @three-count => @producer-count))
  (fact "modifies work units"
    (let [count (atom 0)
          one-store (atom nil)
          two-store (atom nil)
          three-store (atom nil)
          pipe (stream/pipeline (producer count 1)
                                (modify-and-store-stage one-store "hello")
                                (modify-and-store-stage two-store "wow")
                                (modify-and-store-stage three-store "whoa"))]
      (stream/start-pipeline! pipe)
      (Thread/sleep 200)
      (stream/stop-pipeline! pipe)
      (.getCustomerId @one-store) => "hi"
      (.getCustomerId @two-store) => "hello"
      (.getCustomerId @three-store) => "wow"))
  (fact "doesn't require the last stage to call next"
    (let [count (atom 0)
          pipe (stream/pipeline (producer count 1)
                                (count-stage count)
                                (count-stage count)
                                (terminal-stage))]
      (stream/start-pipeline! pipe)
      (Thread/sleep 200)
      (stream/stop-pipeline! pipe)
      true => true)))