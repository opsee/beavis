(ns beavis.slate
  (:require [beavis.stream :refer :all]
            [beavis.sql :as sql]
            [clojure.tools.logging :as log]
            [wall.hack :as hack]
            [opsee.middleware.protobuilder :as pb])
  (:import (io.nodyn.runtime RuntimeFactory RuntimeFactory$RuntimeType NodynConfig)
           (io.nodyn Callback)
           (java.util.concurrent CyclicBarrier)
           (org.dynjs.runtime DynJS)
           (co.opsee.proto CheckResult HttpResponse CheckResponse)
           (io.nodyn.runtime.dynjs DynJSRuntime)))

(def db (atom nil))

(defrecord Assertion [key value relationship operand])

(defn load-assertions [pool target]
  (->> (sql/get-assertions pool)
       (reduce (fn [acc record]
                 (let [check-id (:check_id record)
                       recs (get acc check-id [])]
                   (assoc acc check-id (conj recs (map->Assertion record))))) {})
       (reset! target)))

(defn run-assertion [test-assertion runtime assertion response]
  (-> runtime
      .getDefaultExecutionContext
      (.call test-assertion (-> runtime
                                .getGlobalContext
                                .getObject)
             (into-array Object [assertion response]))
      (.get "success")))

(defn run-assertions [test-assertion runtime assertions response]
  (not-any? not (map #(run-assertion test-assertion runtime % response) assertions)))

(defn slate-stage [db-pool assertions]
  (let [factory (RuntimeFactory/init (-> (Object.)
                                         .getClass
                                         .getClassLoader)
                                     RuntimeFactory$RuntimeType/DYNJS)
        nodyn (.newRuntime factory (NodynConfig.))
        runtime (hack/field DynJSRuntime :runtime nodyn)
        slate (atom nil)]
    (reset! db db-pool)
    (reify
      ManagedStage
      (start-stage! [_]
        (let [barrier (CyclicBarrier. 2)]
          (.runAsync nodyn (reify Callback (call [_ _] (.await barrier))))
          (.await barrier)
          (.evaluate runtime "load('jvm-npm.js');")
          (reset! slate (.evaluate runtime "require('./js/slate/index');"))))
      (stop-stage! [_]
        )
      StreamStage
      (submit [_ work next]
        (let [check-id (.getCheckId work)
              responses (.getResponsesList work)
              sertions (get @assertions check-id [])]
          (next
            (-> (.toBuilder work)
                .clearResponses
                (.addAllResponses
                  (for [resp responses
                        :let [http-resp (pb/decode-any (.getResponse resp))]]
                    (-> (.toBuilder resp)
                        (.setPassing (run-assertions @slate runtime sertions http-resp))
                        .build)))
                .build)))))))