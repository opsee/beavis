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
     (sqs/create-queue :queue-name queue-name
                       :attributes {
                                    :VisibilityTimeout 30 ;sec
                                    })
     (reset! queue (sqs/find-queue queue-name)))
   (catch Exception e
     (log/error e "Failed to setup SQS"))))
 
(defn handle-event [event]
  (try
    (do
      ;; For now, gate this so that we're not throwing a ton of exceptions.
      (when @queue
        (sqs/send-message @queue (-> (hash->proto CheckResult event)
                                     .toByteArray
                                     b64/encode
                                     String.))
        (log/info "Sent event to sqs" (generate-string event))))
    (catch Exception e
      (log/error e "Failed to send message to SQS."))))
