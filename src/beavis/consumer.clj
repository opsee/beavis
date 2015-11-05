(ns beavis.consumer
  (:require [beavis.stream :refer :all]
            [clojure.tools.logging :as log])
  (:import (com.github.brainlag.nsq.lookup DefaultNSQLookup)
           (com.github.brainlag.nsq NSQConsumer ServerAddress)
           (com.github.brainlag.nsq.callbacks NSQMessageCallback)
           (co.opsee.proto CheckResult Timestamp)
           (com.google.common.collect Sets)
           (java.io IOException)))

(def consumer (atom nil))


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

;
; (binding [proto/formatter time-formatter] (-> (CheckResult/parseFrom bytes)
;    (proto/proto->hash)
;    (keywordize-keys)))

(defn convert-message [msg]
  (CheckResult/parseFrom (.getMessage msg)))

(defn handle-message [next]
  (reify NSQMessageCallback
    (message [_ msg]
      (let [check-result (convert-message msg)]
        (.finished msg)
        (log/info "check-result" check-result)
        (next check-result))
      nil)))

(defn nsq-stream-producer [nsq-config]
  (reify StreamProducer
    (start-producer! [_ next]
      (let [lookup (nsq-lookup (:lookup nsq-config) (:produce nsq-config))
            topic "_.results"
            channel-id (str (java.util.UUID/randomUUID))]
        (log/info "channel" channel-id)
        (try
          (reset! consumer
                  (.start (NSQConsumer. lookup topic channel-id (handle-message next))))
          (catch Exception e
            (throw (Throwable. "Unable to start consumer." e))))))
    (stop-producer! [_]
      (.stop consumer))))

