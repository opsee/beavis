(ns beavis.alerts.sqs
  (:require [clojure.tools.logging :as log]
            [amazonica.aws.sqs :as sqs]
            [cheshire.core :refer :all]
            [opsee.middleware.protobuilder :refer :all]
            [clojure.data.codec.base64 :as b64])
  (:import (co.opsee.proto CheckResult)))

(def queue (atom nil))

(defn init [config]
 (try
   (let [queue-name (get-in config [:sqs :queue-name])]
     (sqs/create-queue {:endpoint "us-west-2"} 
                       :queue-name queue-name
                       :attributes {
                                    :VisibilityTimeout 30 ;sec
                                    })
     (reset! queue (sqs/find-queue {:endpoint "us-west-2"} queue-name)))
   (catch Exception e
     (log/error e "Failed to setup SQS"))))
 
(defn handle-event [event]
  (try
    (do
      ;; For now, gate this so that we're not throwing a ton of exceptions.
      (when @queue
        (let [secs (:timestamp event)
              event' (assoc event :timestamp {:seconds secs})]
          (sqs/send-message {:endpoint "us-west-2"} @queue (-> (hash->proto CheckResult event')
                                       .toByteArray
                                       b64/encode
                                       String.))
          (log/info "Sent event to sqs" (generate-string event')))))
    (catch Exception e
      (log/error e "Failed to send message to SQS."))))
