(require '[riemann.streams :refer :all]
         '[riemann.core :as core]
         '[beavis.habilitationsschrift :refer [next-stage-fn]]
         '[clojure.tools.logging :as log])

(defn set-response-state [event]
  (let [mutated (assoc event :state (:passing event))]
    mutated))

(defn set-result-state [event]
  (let [passing (every? #(= (:passing %) true) (:responses event))
        mutated (assoc event :passing passing :state passing)]
    mutated))

(defn set-state [& children]
  (fn [event]
    (cond (:responses event) (call-rescue (set-result-state event) children)
          (:response event) (call-rescue (set-response-state event) children)
          :else nil)))

(defn pass-to-next-stage [& children]
  (fn [event]
    (next-stage-fn event)
    (call-rescue event children)))

(let [index (core/wrap-index (index))]
  ;; This stream must be, in total, synchronous. Any asynchronous operations
  ;; must happen in another stream. This stream only updates the in-memory
  ;; state so that after a call to stream!, we can immediately query the
  ;; index to get the state of the mutated event.
  (streams
    (by [:host :service]
        (set-state
          index
          (changed :state
                   (pass-to-next-stage))))))
