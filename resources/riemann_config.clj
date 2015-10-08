(require '[riemann.streams :refer :all]
         '[riemann.core :as core])

(defn to-event [result response]
  (assoc response
    :host (get-in response [:target :id])
    :service (:check_id result)
    :time (:timestamp result)))

(defn parse-stream [& children]
  (fn [result]
    (let [event (assoc result :responses (map #(to-event result %) (:responses result)))]

      (call-rescue (to-event result event) children))))

(defn is-opsee-event [& children]
  (fn [event] (if (not (nil? (:customer_id event))) (call-rescue event children) nil)))

(let [index (core/wrap-index (index))]
  (streams
    (is-opsee-event
      (parse-stream
        (by [:customer_id :check_id]
            (fn [event] (index event)))))))