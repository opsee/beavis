(ns beavis.habilitationsschrift
  "CoreStreamer streams events through a Riemann Core via submit and then passes
  the result of stream processing along to the next StreamStage."
  (:require [clojure.java.io :as io]
            [riemann.bin]
            [riemann.config :refer [core]]
            [riemann.core]
            [riemann.index :as index]
            [riemann.pubsub :refer [PubSub]]
            [riemann.streams]
            [riemann.time]
            [beavis.stream :refer :all]
            [clojure.tools.logging :as log]))

(defn to-event [result response]
  (assoc response
    :host (get-in response [:target :id])
    :service (:check_id result)
    :time (:timestamp result)))

(defn to-riemann-event [work]
    (let [event (assoc work :responses (map #(to-event work %) (:responses work)))]
      (to-event event work)))

(defn handle-event [work next]
  (let [event (to-riemann-event work)
        index (:index @core)
        host (:host event)
        service (:service event)]
    ;; The streams that update the index must be synchronous.
    (riemann.core/stream! @core event)
    (next (index/lookup index host service))))

(defn CoreStreamer [threadCount]
  (reify
    ManagedStage
    (start-stage! [_]
      (let [config-file (.getFile (io/resource "riemann_config.clj"))]
        (riemann.bin/handle-signals)
        (riemann.time/start!)
        (riemann.config/include config-file)
        (riemann.config/apply!)
        (riemann.config/start!)
        nil))
    (stop-stage! [_]
      (riemann.time/stop!)
      (riemann.config/stop!)
      nil)

    StreamStage
    (submit [_ work next]
      (handle-event work next))))