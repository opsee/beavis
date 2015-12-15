(ns beavis.deletions
  (:require [verschlimmbesserung.core :as v]
            [clojure.tools.logging :as log]
            [beavis.habilitationsschrift :as hab]
            [clojure.string :as str]))


(def path "/opsee.co/deletions")
(defn delete-path [check-id] (str path "/" check-id))

(def deleted-checks (atom {}))

(defn reload-deletes [response]
  (reset! deleted-checks (into {} (map (fn [node]
                                         [(-> node :key (str/split #"/") last) (:value node)]))
                               (get-in response [:node :nodes])))
  (log/info "loaded deletions" @deleted-checks)
  (hab/wait-for-index)
  (hab/delete-all-results @deleted-checks))

(defn delete-check [client customer-id check-id]
  (try
    (swap! deleted-checks assoc check-id customer-id)
    (hab/delete-all-results {check-id customer-id})
    (v/create! client (delete-path check-id) customer-id {:ttl 5600})
    (catch Exception ex (log/error ex "trouble talking to etcd"))))

(defn is-deleted? [check-id]
  (contains? @deleted-checks check-id))
