(ns beavis.t-slate
  (:use midje.sweet
        opsee.middleware.test-helpers
        beavis.fixtures)
  (:require [beavis.slate :as slate]
            [beavis.stream :as stream]
            [clojure.tools.logging :as log]
            [opsee.middleware.config :refer [config]]))

(def defaults {"DB_NAME" "beavis_test"
               "DB_HOST" "localhost"
               "DB_PORT" "5432"
               "DB_USER" "postgres"
               "DB_PASS" ""})
(def test-config (config "resources/test-config.json" defaults))

(defn do-setup []
  (start-connection test-config))

(facts "slate processes assertions"
  (with-state-changes
    [(before :facts (doto
                      (do-setup)
                      assertion-fixtures))]
    (fact "processes a passing checkresult"
      (let [assertions (atom {})
            slate-stage (slate/slate-stage @db assertions)
            results (check-result "goodcheck" 3 3 20)
            passing (atom 0)
            next (fn [work]
                   (doseq [response (.getResponsesList work)]
                     (when (.getPassing response)
                       (swap! passing inc))))]
        (slate/load-assertions @db assertions)
        (stream/start-stage! slate-stage)
        (stream/submit slate-stage results next)
        @passing => 3))
    (fact "fails a failing check"
      (let [assertions (atom {})
            slate-stage (slate/slate-stage @db assertions)
            results (check-result "goodcheck" 3 0 20)
            passing (atom 0)
            next (fn [work]
                   (doseq [response (.getResponsesList work)]
                     (when (.getPassing response)
                       (swap! passing inc))))]
        (slate/load-assertions @db assertions)
        (stream/start-stage! slate-stage)
        (stream/submit slate-stage results next)
        @passing => 0))
    (fact "fails only the failing checks"
      (let [assertions (atom {})
            slate-stage (slate/slate-stage @db assertions)
            results (check-result "goodcheck" 3 1 20)
            passing (atom 0)
            next (fn [work]
                   (doseq [response (.getResponsesList work)]
                     (when (= true (.getPassing response))
                       (swap! passing inc))))]
        (slate/load-assertions @db assertions)
        (stream/start-stage! slate-stage)
        (stream/submit slate-stage results next)
        @passing => 1))))