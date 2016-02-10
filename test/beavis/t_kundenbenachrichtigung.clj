(ns beavis.t-kundenbenachrichtigung
  (:use midje.sweet
        opsee.middleware.test-helpers)
  (:require [beavis.kundenbenachrichtigung :as k]
            [beavis.stream :as s]
            [beavis.sql :as sql]
            [beavis.fixtures :refer :all]
            [clojure.tools.logging :as log]
            [opsee.middleware.config :refer [config]]
            [opsee.middleware.protobuilder :as proto]
            [beavis.alerts.sqs :as sqs]))

(def cliff-id "154ba57a-5188-11e5-8067-9b5f2d96dce1")
(def greg-id "154ba57a-5188-11e5-8067-9b5f2d96dce2")
(def open-alert-check "hello")
(def resolved-alert-check "poop")
(def unseen-check-id "1")
(defn passing-event [customer_id check_id]
  (assoc (proto/proto->hash (check-result 3 3 0)) :customer_id customer_id :check_id check_id :check_name check_id :passing true))
(defn failing-event [customer_id check_id]
  (assoc (proto/proto->hash (check-result 3 1 0)) :customer_id customer_id :check_id check_id :check_name check_id :passing false))

(def sqs-event (atom nil))

(defn next-fn [event]
  event)

(def defaults {"DB_NAME" "beavis_test",
               "DB_HOST" "localhost",
               "DB_PORT" "5432",
               "DB_USER" "postgres",
               "DB_PASS" ""})
(def test-config (config "resources/test-config.json" defaults))

(defn do-setup []
  (do
    (log/info test-config defaults)
    (start-connection test-config)))

(facts
  "sending alerts"
  (with-redefs [sqs/handle-event (fn [event] (reset! sqs-event event))]
    (with-state-changes [(before :facts (do
                                          (doto (do-setup)
                                          alert-fixtures)
                                          (reset! sqs-event nil)))]
                        (fact "creates an alert if no previous alert and failing event"
                              (let [stage (k/alert-stage @db test-config)
                                    event (failing-event greg-id unseen-check-id)]
                                (s/start-stage! stage next-fn)
                                (s/submit stage event) => event
                                (let [alert (first (sql/get-latest-alert @db {:customer_id greg-id
                                                                              :check_id    unseen-check-id}))]
                                  (:state alert) => "open"
                                  (str (:customer_id alert)) => greg-id
                                  (:check_id alert) => unseen-check-id
                                  @sqs-event => event)
                                (s/stop-stage! stage)))
                        (fact "creates an alert if previous alert was resolved"
                              (let [stage (k/alert-stage @db test-config)
                                    event (failing-event cliff-id resolved-alert-check)]
                                (s/start-stage! stage next-fn)
                                (s/submit stage event) => event
                                (let [alert (first (sql/get-latest-alert @db {:customer_id cliff-id
                                                                              :check_id    resolved-alert-check}))]
                                  (:state alert) => "open"
                                  (str (:customer_id alert)) => cliff-id
                                  (:check_id alert) => resolved-alert-check
                                  @sqs-event => event)
                                (s/stop-stage! stage)))
                        (fact "resolves an alert if passing event and previous open alert"
                              (let [stage (k/alert-stage @db test-config)
                                    event (passing-event cliff-id open-alert-check)]
                                (s/start-stage! stage next-fn)
                                (s/submit stage event) => event
                                (let [alert (first (sql/get-latest-alert @db {:customer_id cliff-id
                                                                              :check_id    open-alert-check}))]
                                  (:state alert) => "resolved"
                                  (str (:customer_id alert)) => cliff-id
                                  (:check_id alert) => open-alert-check
                                  @sqs-event => event)
                                (s/stop-stage! stage))))))
