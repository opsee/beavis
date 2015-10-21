(require '[riemann.streams :refer :all]
         '[riemann.core :as core])

;; Results contain many responses.
(defn is-result [& children]
  (fn [event] (when (:responses event) (call-rescue event children))))

;; Responses only contain a single response.
(defn is-response [& children]
  (fn [event] (when (:response event) (call-rescue event children))))

(defn set-response-state [& children]
  "Copies the 'passing' field into :state for Riemann."
  (fn [event]
    (let [mutated (assoc event :state (:passing event))]
      (call-rescue mutated children))))

(defn set-result-state [& children]
  (fn [event]
    (let [passing (every? #(= (:passing %) true) (:responses event))
          mutated (assoc event :passing passing :state passing)]
      (call-rescue mutated children))))

(defn index-if-not-indexed [& children]
  (fn [event]
    (let [indexed (riemann.index/lookup (:index @core) (:host event) (:service event))]
      (when (nil? indexed) (call-rescue event children)))))

(let [index (core/wrap-index (index))]
  ;; This stream must be, in total, synchronous. Any asynchronous operations
  ;; must happen in another stream. This stream only updates the in-memory
  ;; state so that after a call to stream!, we can immediately query the
  ;; index to get the state of the mutated event.
  (streams
    ;; All events are indexed if not currently indexed.
    (index-if-not-indexed index)
    ;; Handle opsee events
    (is-result
      ;; Copy :passing into :state
      ;; Determine if the event is passing or failing.
      (set-result-state
        (by [:customer_id :check_id]
            index)))
    (is-response
      (set-response-state
        (by [:host :service]
            index)))))
