(ns beavis.kundenbenachrichtigung
  (:require [beavis.alerts.email :as email]
            [beavis.stream :refer :all]
            [beavis.sql :as sql]
            [clojure.tools.logging :as log]))

(def db (atom nil))
(def config (atom nil))

(defn resolve-predicate [event alert]
  (and (:passing event) (= (:state alert) "open")))

(defn create-predicate [event alert]
  (or
    (and (nil? alert) (not (:passing event)))
    (and (not (:passing event)) (= (:state alert) "resolved"))))

(defn send-messages [event notifications]
  (doseq [notification notifications]
    (case (:type notification)
      "email" (email/handle-event event notification)
      (log/info "No notification handler registered for type" (:type notification)))))

(defn handle-event [event]
  "handle-event wraps individual alert integrations with some logic that dictates whether or
  not an event should be passed to a handler for notifications.

   given an event and alert, determine if we should handle the event:
   if the event is a failing event, and the latest alert is in a resolved state, then true.
   if the event is failing and alert is nil, then true.
   if the event is passing, and the alert is open, then true.

  Individual alert integrations are pretty dumb. They just know what to do based on a configuration
  and an event. If the event is passing, do X. If the event is failing, do Y. Etc. The state transitions
  are inherently tied to the state of the event. It is assumed, in handlers, that if an event is passing,
  then it was previously okay."
  (let [event-id {:customer_id (:customer_id event) :check_id (:check_id event) :check_name (or (:check_name event)
                                                                                                "")}
        alert (first (sql/get-latest-alert @db event))
        notifications (sql/get-notifications-by-check-and-customer @db event-id)]
    (log/debug "Handling event: event=" (event-for-logging event) " alert=" alert " notifications=" notifications)
    (when (resolve-predicate event alert)
      (log/info "Resolving alert for event: " event-id)
      (sql/resolve-alert! @db {:alert_id (:id alert)})
      (send-messages event notifications))
    (when (create-predicate event alert)
      (log/info "Creating alert for event: " event-id)
      (sql/create-alert! @db event-id)
      (send-messages event notifications))))

(defn alert-stage [db-conn cfg]
  (let [next (atom nil)]
    (reify
      ManagedStage
      (start-stage! [this cb]
        (reset! next cb)
        (reset! db db-conn)
        (reset! config cfg)
        (email/init cfg))
      (stop-stage! [this]
        (reset! db nil)
        (reset! config nil))
      StreamStage
      (submit [this work]
        (do
          (handle-event work)
          (@next work))))))
