(ns beavis.alerts.sqs
  (:require [clojure.tools.logging :as log]
            [cemerick.bandalore :as sqs]
            [cheshire.core :refer :all]))

(def client atom)
(def queue atom)


(defn init [config]
 (try 
    (reset! client (sqs/create-client))
    (reset! queue (sqs/create-queue client (get-in config [:sqs :queue-name])))
    (catch Exception e (str "Exception: " (.getMessage e)))))
 
(defn handle-event [event]
  (try
    (do 
      (sqs/send @client @queue (generate-string event))
      (log/info "Sent event to sqs" (generate-string event)))
    (catch Exception e (str "Error: " (.getMessage e)))))
