(ns beavis.slate
  (:require [beavis.stream :refer :all]
            [beavis.sql :as sql]
            [clojure.tools.logging :as log]
            [wall.hack :as hack]
            [opsee.middleware.protobuilder :as pb]
            [opsee.middleware.core :refer [report-exception]])
  (:import (io.nodyn.runtime RuntimeFactory RuntimeFactory$RuntimeType NodynConfig)
           (io.nodyn Callback)
           (java.util.concurrent CyclicBarrier)
           (org.dynjs.runtime DynJS DynObject)
           (co.opsee.proto CheckResult HttpResponse CheckResponse Any)
           (io.nodyn.runtime.dynjs DynJSRuntime)
           (com.google.protobuf Descriptors Descriptors$FieldDescriptor GeneratedMessage WireFormat$JavaType)
           (clojure.lang Reflector)))

(def db (atom nil))

(defrecord Assertion [key value relationship operand])
(defrecord JibberScript [slate runtime])

(defn load-assertions [pool target]
  (->> (sql/get-assertions pool)
       (reduce (fn [acc record]
                 (let [check-id (:check_id record)
                       recs (get acc check-id [])]
                   (assoc acc check-id (conj recs (map->Assertion record))))) {})
       (reset! target))
  (log/info "loaded assertions" @target))

(declare proto->js)

(defn run-assertion [jibberscript assertion response]
  (try
    (let [runtime (:runtime jibberscript)
          response (proto->js (.getDefaultExecutionContext runtime) response)
          result (-> runtime
                     .getDefaultExecutionContext
                     (.call (:slate jibberscript) (-> (:runtime jibberscript)
                                                      .getGlobalContext
                                                      .getObject)
                            (into-array Object [assertion response])))]
      (log/info "assertion" assertion "response" response "result" result (.get result "success"))
      (.get result "success"))
    (catch Exception ex (do
                          (report-exception ex {:assertion assertion
                                                :response (pb/proto->hash response)})
                          (throw ex)))))

(defn run-assertions [jibberscript assertions response]
  (not-any? not (map #(run-assertion jibberscript % response) assertions)))


(defn any->js [ec ^Any any]
  (let [type (.getTypeUrl any)
        clazz (Class/forName (str "co.opsee.proto." type))
        proto (Reflector/invokeStaticMethod clazz "parseFrom" (to-array [(.getValue any)]))]
    (proto->js ec proto)))

(defn- unpack-value [ec ^Descriptors$FieldDescriptor field value]
  (pb/case-enum (.getJavaType field)
                WireFormat$JavaType/BOOLEAN value
                WireFormat$JavaType/BYTE_STRING value
                WireFormat$JavaType/DOUBLE value
                WireFormat$JavaType/ENUM value
                WireFormat$JavaType/FLOAT value
                WireFormat$JavaType/INT value
                WireFormat$JavaType/LONG value
                WireFormat$JavaType/STRING value
                WireFormat$JavaType/MESSAGE (case (.getName (.getMessageType field))
                                              "Any" (any->js ec value)
                                              (proto->js ec value))))

(defn unpack-repeated-or-single [ec ^Descriptors$FieldDescriptor field value]
  (if (.isRepeated field)
    (into-array Object (mapv (partial unpack-value ec field) value))
    (unpack-value ec field value)))

(defn proto->js [ec ^GeneratedMessage proto]
  (let [js (DynObject.)]
    (doseq [[^Descriptors$FieldDescriptor field value] (.getAllFields proto)
            :let [unpacked (unpack-repeated-or-single ec field value)]]
      (.put js ec (.getName field) unpacked true))
    js))

(defn init-jibberscript []
  (let [factory (RuntimeFactory/init (-> (Object.)
                                         .getClass
                                         .getClassLoader)
                                     RuntimeFactory$RuntimeType/DYNJS)
        nodyn (.newRuntime factory (NodynConfig.))
        runtime (hack/field DynJSRuntime :runtime nodyn)
        barrier (CyclicBarrier. 2)]
    (.runAsync nodyn (reify Callback (call [_ _] (.await barrier))))
    (.await barrier)
    (.evaluate runtime "load('jvm-npm.js');")

    (let [jibber (->JibberScript (.evaluate runtime "require('./js/slate/index');") runtime)]
      (log/info "initialized javascript runtime" jibber)
      jibber)))

(defn slate-stage [db-pool assertions]
  (let [jibberscript (atom nil)
        next (atom nil)]
    (reset! db db-pool)
    (reify
      ManagedStage
      (start-stage! [_ next-fn]
        (reset! next next-fn)
        (reset! jibberscript (init-jibberscript)))
      (stop-stage! [_]
        )
      StreamStage
      (submit [_ work]
        (if (< (.getVersion work) 1)
          (do
            (let [check-id (.getCheckId work)
                  responses (.getResponsesList work)
                  sertions (get @assertions check-id [])]
              (while
                (not
                  (try
                    (@next
                      (-> (.toBuilder work)
                          .clearResponses
                          (.addAllResponses
                            (for [resp responses]
                              (if (.hasResponse resp)
                                (let [http-resp (pb/decode-any (.getResponse resp))]
                                  (-> (.toBuilder resp)
                                      (.setPassing (run-assertions @jibberscript sertions http-resp))
                                      .build))
                                (-> (.toBuilder resp)
                                    (.setPassing false)
                                    .build))))
                          .build))
                    true
                    (catch IllegalStateException _
                      (do
                        (reset! jibberscript (init-jibberscript))
                        false)))))))
          (@next work))))))
