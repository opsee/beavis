(ns beavis.consumer
  (:require [beavis.protobuilder :refer :all]
            [beavis.stream :refer :all]
            [clojure.walk :refer [keywordize-keys]]
            [clojure.tools.logging :as log])
  (:import (com.github.brainlag.nsq.lookup DefaultNSQLookup)
           (com.github.brainlag.nsq NSQConsumer)
           (com.github.brainlag.nsq.callbacks NSQMessageCallback)
           (co.opsee.proto CheckResult)))

(def consumer (atom nil))

(defn convert-message [msg]
  (let [bytes (.getMessage msg)]
     (-> (CheckResult/parseFrom bytes)
         (proto->hash)
         (keywordize-keys))))

(defn handle-message [next]
  (reify NSQMessageCallback
    (message [_ msg]
      (let [check-result (convert-message msg)]
        (.finished msg)
        (next check-result))
      nil)))

(defrecord ResultConsumer []
  StreamProducer
  (start-producer! [_ next]
    (let [lookup (DefaultNSQLookup.)
          topic "_.results"]
      (try
        (reset! consumer
                (.start (NSQConsumer. lookup topic (str (java.util.UUID/randomUUID)) (handle-message next))))
        (catch Exception e
          (throw (Throwable. "Unable to start consumer." e))))))
  (stop-producer! [_]
    (.stop consumer)))
