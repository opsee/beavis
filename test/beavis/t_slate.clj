(ns beavis.t-slate
  (:use midje.sweet
        opsee.middleware.test-helpers
        beavis.fixtures)
  (:require [beavis.slate :as slate]
            [beavis.stream :as stream]
            [clojure.tools.logging :as log]
            [opsee.middleware.migrate :refer [migrate-db]]
            [opsee.middleware.config :refer [config]]
            [opsee.middleware.pool :refer [pool]]))

;; For now we have to duplicate this garbage here as well (from t_api.clj)
(def defaults {"DB_NAME" "beavis_test",
               "DB_HOST" "beavis_dbhost",
               "DB_PORT" "5432",
               "DB_USER" "postgres",
               "DB_PASS" ""})
(def test-config (config "resources/test-config.json" defaults))
(def bartnet-db (atom nil))

(defn do-setup []
  (do
    (log/info test-config defaults)
    ;; It's okay if we have two test databases that are identical. We only
    ;; use the assertions table in this one but the schema are mostly the
    ;; same.
    (when-not @bartnet-db 
      (reset! bartnet-db (pool (:bartnet-db-spec test-config)))
      (migrate-db @bartnet-db {:drop-all true :silent true}))
    (start-connection test-config)))

(facts "slate processes assertions"
  (with-state-changes
    [(before :facts (do
                      (do-setup)
                      (assertion-fixtures @bartnet-db)))]
    (fact "ignores that a CheckResult of version < 1"
      (let [assertions (atom {})
            slate-stage (slate/slate-stage @bartnet-db assertions)
            result (-> (check-result 1 3 0)
                       .toBuilder
                       (.setVersion 1)
                       .build)
            invoked (atom false)
            next (fn [work] (reset! invoked true))]
        (slate/load-assertions @bartnet-db assertions)
        (stream/start-stage! slate-stage next)
        (stream/submit slate-stage result)
        @invoked => false))
    (fact "processes a passing checkresult"
      (let [assertions (atom {})
            slate-stage (slate/slate-stage @bartnet-db assertions)
            results (check-result "goodcheck" 3 3 20)
            passing (atom 0)
            next (fn [work]
                   (doseq [response (.getResponsesList work)]
                     (when (.getPassing response)
                       (swap! passing inc))))]
        (slate/load-assertions @bartnet-db assertions)
        (stream/start-stage! slate-stage next)
        (stream/submit slate-stage results)
        @passing => 3))
    (fact "fails a failing check"
      (let [assertions (atom {})
            slate-stage (slate/slate-stage @bartnet-db assertions)
            results (check-result "goodcheck" 3 0 20)
            passing (atom 0)
            next (fn [work]
                   (doseq [response (.getResponsesList work)]
                     (when (.getPassing response)
                       (swap! passing inc))))]
        (slate/load-assertions @bartnet-db assertions)
        (stream/start-stage! slate-stage next)
        (stream/submit slate-stage results)
        @passing => 0))
    (fact "fails only the failing checks"
      (let [assertions (atom {})
            slate-stage (slate/slate-stage @bartnet-db assertions)
            results (check-result "goodcheck" 3 1 20)
            passing (atom 0)
            next (fn [work]
                   (doseq [response (.getResponsesList work)]
                     (when (= true (.getPassing response))
                       (swap! passing inc))))]
        (slate/load-assertions @bartnet-db assertions)
        (stream/start-stage! slate-stage next)
        (stream/submit slate-stage results)
        @passing => 1))))
