(ns beavis.alerts.sqs
  (:require [byte-streams :as bs]
            [clojure.tools.logging :as log]
            [clojure.string :refer [join]]
            [cemerick.bandalore :as sqs]
            [cheshire.core :refer :all]
            [manifold.stream :as s]
            [aleph.http :as http]))

(def client (atom nil))
(def queue (atom nil))
(def config (atom {}))

(defn init [cfg]
 (try 
    (reset! client (sqs/create-client))
    (reset! queue (sqs/create-queue client (get-in cfg [:sqs :queue-name])))
    (reset! config cfg)
    (catch Exception e (str "Exception: " (.getMessage e)))))

(defn retry-handler [ex try-count http-context]
  (log/error ex "Exception encountered when attempting http connection")
  (if (> try-count 5) false true))

(defn string-body [resp]
  (->> resp
       :body
       #(s/map bs/to-byte-array %)
       #(s/reduce conj [] %)
       bs/to-string))

(defn handle-event [event]
  (let [bartnet-uri (join (get-in @cfg [:sqs :bartnet-uri]) "/checks/" (:check_id event))
        notificaption-uri (join (get-in @cfg [:sqs :notificaption-uri]) "/screenshot")
        check (try
                ;; First query bartnet to get the check defn + assertions
                (-> @(http/get bartnet-uri {:retry-handler retry-handler}) string-body)
                (catch Exception ex
                  (log/error ex "Error getting check from Bartnet.")
                  (raise ex)))
        notif-s3-uri (when check
                        (-> @(http/get notificaption-uri {:retry-handler retry-handler} string-body)))
        message {:type "alert" :paramters { :check check :s3_url notif-s3-uri }}]
      (do
        ;; allow this to raise an exception for now, i'd rather it was yeller'd than just logged.
        (sqs/send @client @queue (generate-string event))
        (log/info "Sent event to sqs" (generate-string event)))))
