(ns beavis.assertions
  (:require [verschlimmbesserung.core :as v]
            [beavis.slate :as slate]
            [clojure.tools.logging :as log]
            [opsee.middleware.nsq :refer [ensure-int]]))

(def assertions (atom {}))
(def path "/opsee.co/assertions")

(defn wait-assertions [client index]
  (loop [result (atom nil)]
    (try
      (reset! result (v/get* client path {:wait true :wait-index index :timeout 300}))
      (catch Exception _))
    (if-not @result
      (recur result)
      @result)))

(defn reload-assertions [client pool]
  (try
    (let [response (v/get* client path)]
      (log/info "reloading assertions")
      (slate/load-assertions pool assertions)
      (-> response meta :etcd-index Integer/parseInt inc))
    (catch Exception _ 0)))

(defn watcher [conf pool]
  (fn []
    (let [client (v/connect (:etcd conf))]
      (loop [index (reload-assertions client pool)]
        (wait-assertions client index)
        (recur (reload-assertions client pool))))))

(defn thread-never-die [f]
  (fn []
    (loop []
      (try
        (f)
        (catch Throwable ex (log/error ex "an error occurred in watcher thread " (-> (Thread/currentThread) .getName))))
      (recur))))

(defn start-watcher [conf pool]
  (doto (Thread. (thread-never-die (watcher conf pool)))
        (.setName "assertions-watcher")
        .start))


(defn trigger-reload [client]
  (try
    (v/swap! client path #(inc (ensure-int %)))
    (catch Exception _ nil)))