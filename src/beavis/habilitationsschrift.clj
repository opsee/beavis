(ns beavis.habilitationsschrift
  "CoreStreamer streams events through a Riemann Core via submit and then passes
  the result of stream processing along to the next StreamStage."
  (:require [clojure.java.io :as io]
            [riemann.bin]
            [riemann.config :refer [core]]
            [riemann.core]
            [riemann.pubsub :refer [PubSub]]
            [riemann.streams]
            [riemann.time]
            [beavis.stream :refer :all]
            [clojure.tools.logging :as log]))

(defn handle-event [work next]
  (next (riemann.core/stream! @core work)))

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