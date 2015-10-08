(require '[riemann.streams :refer :all]
         '[riemann.core :as core])

(defn is-opsee-event [& children]
  (fn [event] (if (not (nil? (:customer_id event))) (call-rescue event children) nil)))

(let [index (core/wrap-index (index))]
  ;; This stream must be, in total, synchronous. Any asynchronous operations
  ;; must happen in another stream. This stream only updates the in-memory
  ;; state so that after a call to stream!, we can immediately query the
  ;; index to get the state of the mutated event.
  (streams
    (is-opsee-event
      (by [:customer_id :check_id]
          (fn [event] (index event))))))