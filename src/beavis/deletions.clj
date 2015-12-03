(ns beavis.deletions
  (:require [verschlimmbesserung.core :as v]
            [clojure.tools.logging :as log]
            [beavis.habilitationsschrift :as hab]
            [clojure.string :as str])
  (:import (java.net SocketTimeoutException)
           (co.opsee.proto CheckResult)))


(def path "/opsee.co/deletions")
(defn delete-path [check-id] (str path "/" check-id))

(def deleted-checks (atom {}))

(defn wait-deletes [client index]
  (loop [result (atom nil)]
    (try
      (reset! result (v/get* client path {:wait? true :recursive? true :wait-index index :timeout 300}))
      (log/info "wait" index result)
      (catch Exception _))
    (if-not @result
      (recur result)
      @result)))

(defn reload-deletes [client]
  (try
    (let [response (v/get* client path)]
      (reset! deleted-checks (into {} (map (fn [node]
                                             [(-> node :key (str/split #"/") last) (:value node)]))
                                   (get-in response [:node :nodes])))
      (log/info "loaded deletions" @deleted-checks)
      (hab/delete-all-results @deleted-checks)
      (-> response meta :etcd-index Integer/parseInt inc))
    (catch Exception _ 0)))

(defn watcher [conf]
  (fn []
    (let [client (v/connect (:etcd conf))]
      (loop [index (reload-deletes client)]
        (wait-deletes client index)
        (recur (reload-deletes client))))))

(defn start-deletion-watcher [conf]
  (doto (Thread. (watcher conf))
        .start))

(defn delete-check [client customer-id check-id]
  (try
    (swap! deleted-checks assoc check-id customer-id)
    (hab/delete-all-results {check-id customer-id})
    (v/create! client (delete-path check-id) customer-id {:ttl 5600})
    (catch Exception ex (log/error ex "trouble talking to etcd"))))

(defn is-deleted? [check-id]
  (contains? @deleted-checks check-id))
