(ns beavis.habilitationsschrift
  "CoreStreamer streams events through a Riemann Core via submit and then passes
  the result of stream processing along to the next StreamStage."
  (:require [clojure.java.io :as io]
            [riemann.bin]
            [clojure.walk :refer [keywordize-keys]]
            [opsee.middleware.protobuilder :as proto]
            [riemann.config :refer [core]]
            [riemann.core]
            [clojure.string :as str]
            [riemann.query :as query-parser]
            [riemann.index :as index]
            [riemann.pubsub :refer [PubSub]]
            [riemann.streams]
            [riemann.time]
            [beavis.stream :refer :all]
            [clojure.tools.logging :as log])
  (:import (co.opsee.proto Timestamp)
           (clojure.lang IPersistentMap)))

(defn time-formatter [^Timestamp t]
  (.getSeconds t))

(defn q [params]
  (str/join " and " (map (fn [[k v]] (str (name k) " = \"" v "\"")) params)))

(defmulti query (fn [qu] (type qu)))
(defmethod query IPersistentMap [qu]
  (query (q qu)))
(defmethod query String [qu]
  (index/search (:index @core) (query-parser/ast qu)))

(defn delete [event]
  (index/delete (:index @core) event))

(defn delete-all-results [deletions]
  (doseq [[check-id customer-id] deletions]
    (let [results (query {:service check-id
                          :customer_id customer-id})]
      (doseq [event results]
        (delete event)))))

(defn get-config-path []
  "In a container, we use /etc/beavis/riemann_config.clj, but when testing
  or running locally, we use it from the classpath."
  (let [config-file "riemann_config.clj"
        etc-config  (str "/beavis/etc/" config-file)
        resource-config (.getPath (io/resource config-file))]
    (if (-> etc-config (io/file) (.exists))
      etc-config
      (do
        (log/info "Unable to locate Riemann config in /beavis/etc")
        resource-config))))

(defn to-event [result response]
  (assoc response
    :host (or (get-in response [:target :id]) (get-in response [:target :name]))
    :service (:check_id result)
    :customer_id (:customer_id result)
    :check_id (:check_id result)
    :time (:timestamp result)))

(defn to-riemann-event [result-pb]
    (let [result (binding [proto/formatter time-formatter] (-> result-pb
                                                               (proto/proto->hash)
                                                               (keywordize-keys)))
          event (assoc result :responses (map #(assoc (to-event result %) :type "response")
                                              (:responses result))
                              :type "result")
          mutated (to-event event event)]
      mutated))

(defn stream-and-return [event]
  (riemann.core/stream! @core event)
  (index/lookup (:index @core) (:host event) (:service event)))

(def  ^:dynamic next-stage-fn)

(defn handle-event [result next]
  "Handle Results and Responses separately in the same Riemann core.

  When an event is received in this stream stage, we first pull the
  Responses out of the Result and run them through the Riemann stream.
  Once through, we then query the index to retrieve the current state
  of affairs for that (host, service) tuple and replace the responses
  with those contained in the Riemann index. It then passes this
  mutated Result through the stream for further inspection by Riemann.

  The returned, modified Result is then passed on to the next stage
  of stream processing.

  A trivial example:

  If the Index currently contains the following for (host a, service a):
  {:host A, :service A, :state ok}

  And the following \"Result\" is passed into the stream:
  {:target 'group', :responses {:host A, :service A, :state bad}}

  What comes out of the Riemann stream, may look like:
  {:target 'group', :responses {:host A, :service A, :state ok}}

  This may happen in the case that this was considered a service flap
  by Riemann.
  "
  (let [event (to-riemann-event result)
        responses (map stream-and-return (:responses event))]
    (binding [next-stage-fn next]
      (stream-and-return (assoc event :responses responses)))))

(defn riemann-stage []
  (reify
    ManagedStage
    (start-stage! [_]
      (let [config-file (get-config-path)]
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
