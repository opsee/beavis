(ns beavis.consumer
  (:require [beavis.protobuilder :as proto]
            [beavis.stream :refer :all]
            [clojure.walk :refer [keywordize-keys]]
            [clojure.tools.logging :as log])
  (:import (com.github.brainlag.nsq.lookup DefaultNSQLookup)
           (com.github.brainlag.nsq NSQConsumer ServerAddress)
           (com.github.brainlag.nsq.callbacks NSQMessageCallback)
           (co.opsee.proto CheckResult)
           (com.google.common.collect Sets)
           (java.io IOException)))

(def consumer (atom nil))
(proto/set-format "Timestamp" "int64")

(defn ensure-int [val]
  (if (= String (class val))
    (try
      (Integer/parseInt val)
      (catch Exception _ 0))
    val))

(defn nsq-lookup [lookup-addr produce-addr]
  (let [proxy (proxy [DefaultNSQLookup] []
                (lookup [topic]
                  (try
                    (proxy-super lookup topic)
                    (catch IOException _
                      (let [set (Sets/newHashSet)]
                        (.add set (ServerAddress. (:host produce-addr) (ensure-int (:port produce-addr))))
                        set)))))]
    (.addLookupAddress proxy (:host lookup-addr) (ensure-int (:port lookup-addr)))
    proxy))

(defn convert-message [msg]
  (let [bytes (.getMessage msg)]
     (-> (CheckResult/parseFrom bytes)
         (proto/proto->hash)
         (keywordize-keys))))

(defn handle-message [next]
  (reify NSQMessageCallback
    (message [_ msg]
      (let [check-result (convert-message msg)]
        (.finished msg)
        (next check-result))
      nil)))

(defn nsq-stream-producer [nsq-config]
  (reify StreamProducer
    (start-producer! [_ next]
      (let [lookup (nsq-lookup (:lookup nsq-config) (:produce nsq-config))
            topic "_.results"]
        (try
          (reset! consumer
                  (.start (NSQConsumer. lookup topic (str (java.util.UUID/randomUUID)) (handle-message next))))
          (catch Exception e
            (throw (Throwable. "Unable to start consumer." e))))))
    (stop-producer! [_]
      (.stop consumer))))

