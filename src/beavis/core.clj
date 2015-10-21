(ns beavis.core
  (:gen-class)
  (:require [beavis.api :as api]
            [clojure.tools.logging :as log]
            [opsee.middleware.migrate :as migrate]
            [opsee.middleware.config :refer [config]]
            [opsee.middleware.pool :refer [pool]]
            [beavis.stream :as stream]
            [beavis.consumer :as consumer]
            [verschlimmbesserung.core :as v]
            [ring.adapter.jetty :refer [run-jetty]]
            [beavis.slate :as slate])
  (:import (java.net SocketTimeoutException)))

(defn watch-for-change [client]
  (loop [result (atom nil)]
    (try
      (reset! result (v/get client "/opsee.co/assertions" {:wait? true :timeout 30}))
      (catch SocketTimeoutException _))
    (if-not @result
      (recur result)
      @result)))

(defn watcher [conf pool assertions]
  (fn []
    (let [client (v/connect (:etcd conf))]
      (loop []
        (try
          (slate/load-assertions pool assertions)
          (log/info "refreshing assertions data" (watch-for-change client))
          (catch Exception ex (log/error "" ex)))
        (recur)))))

(defn start-watcher [conf pool assertions]
  (doto (Thread. (watcher conf pool assertions))
        .start))

(defn start-stream [conf pool]
  (let [assertions (atom {})
        assertions-watcher (start-watcher conf pool assertions)
        pipeline (stream/pipeline (consumer/nsq-stream-producer (:nsq conf))
                                  (slate/slate-stage pool assertions))]
    (stream/start-pipeline! pipeline)))

(defn start-server [args]
  (let [conf (config (last args))
        db (pool (:db-spec conf))]
    (start-stream conf db)
    (run-jetty (api/handler db conf) (:server conf))))

(defn -main [& args]
  (let [cmd (first args)
        subargs (rest args)]
    (case cmd
      "server" (start-server subargs)
      "db" (migrate/db-cmd subargs))))
