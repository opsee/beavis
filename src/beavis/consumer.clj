(ns beavis.consumer
  (:require [beavis.stream :refer :all]
            [beavis.deletions :as deletions]
            [opsee.middleware.nsq :refer [nsq-lookup]]
            [clojure.tools.logging :as log]
            [beavis.deletions :as deletions])
  (:import (com.github.brainlag.nsq NSQConsumer)
           (com.github.brainlag.nsq.callbacks NSQMessageCallback)
           (co.opsee.proto CheckResult Timestamp)))

(def consumer (atom nil))

(defn convert-message [msg]
  (CheckResult/parseFrom (.getMessage msg)))

(defn handle-message [next]
  (reify NSQMessageCallback
    (message [_ msg]
      (let [check-result (convert-message msg)]
        (.finished msg)
        (if-not (deletions/is-deleted? (.getCheckId check-result))
          (next check-result)))
      nil)))

(defn nsq-stream-producer [nsq-config]
  (reify ManagedStage
    (start-stage! [_ next]
      (let [lookup (nsq-lookup (:lookup nsq-config) (:produce nsq-config))
            topic "_.results"
            channel-id (str (java.util.UUID/randomUUID) "#ephemeral")]
        (log/info "channel" channel-id)
        (try
          (reset! consumer
                  (.start (NSQConsumer. lookup topic channel-id (handle-message next))))
          (catch Exception e
            (throw (Throwable. "Unable to start consumer." e))))))
    (stop-stage! [_]
      (.stop consumer))))

