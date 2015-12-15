(ns beavis.assertions
  (:require [verschlimmbesserung.core :as v]
            [beavis.slate :as slate]
            [clojure.tools.logging :as log]
            [opsee.middleware.nsq :refer [ensure-int]]))

(def assertions (atom {}))
(def path "/opsee.co/assertions")

(defn reload-assertions [pool]
  (slate/load-assertions pool assertions))

(defn trigger-reload [client]
  (try
    (v/swap! client path #(inc (ensure-int %)))
    (catch Exception _ nil)))