(require '[riemann.streams :refer :all]
         '[riemann.core :as core])

(defn is-opsee-event [& children]
  (fn [event] (if (not (nil? (:customer_id event))) (call-rescue event children) nil)))

(defn set-response-states [& children]
  "Copies the 'passing' field into :state for Riemann."
  (fn [event]
    ;; First we must set all response states based on if they are :passing
    ;; or not.
    (let [mutated (assoc event :responses (map #(assoc % :state (:passing %)) (:responses event)))]
      (call-rescue mutated children))))

(defn set-result-state [& children]
  (fn [event]
    (let [passing (every? #(= (:passing %) true) (:responses event))
          mutated (assoc event :passing passing :state passing)]
      (call-rescue mutated children))))

(let [index (core/wrap-index (index))]
  ;; This stream must be, in total, synchronous. Any asynchronous operations
  ;; must happen in another stream. This stream only updates the in-memory
  ;; state so that after a call to stream!, we can immediately query the
  ;; index to get the state of the mutated event.
  (streams
    ;; Handle opsee events
    (is-opsee-event
      ;; Create a stream unique to each check
      (by [:customer_id :check_id]
          ;; Copy :passing into :state
          (set-response-states
            ;; Determine if the event is passing or failing.
            (set-result-state
              index))))))