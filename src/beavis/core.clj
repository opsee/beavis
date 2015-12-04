(ns beavis.core
  (:gen-class)
  (:require [beavis.api :as api]
            [clojure.tools.logging :as log]
            [opsee.middleware.migrate :as migrate]
            [opsee.middleware.config :refer [config]]
            [opsee.middleware.pool :refer [pool]]
            [beavis.stream :as stream]
            [beavis.consumer :as consumer]
            [beavis.deletions :as deletions]
            [beavis.assertions :as assertions]
            [verschlimmbesserung.core :as v]
            [ring.adapter.jetty9 :refer [run-jetty]]
            [beavis.slate :as slate]
            [beavis.habilitationsschrift :as hab]
            [beavis.kundenbenachrichtigung :as alerts])
  (:import (java.net SocketTimeoutException)
           (org.eclipse.jetty.server Server)
           (org.eclipse.jetty.server.handler ErrorHandler)))

(defn watch-for-change [client]
  (loop [result (atom nil)]
    (try
      (reset! result (v/get client "/opsee.co/assertions" {:wait? true :timeout 30}))
      (catch SocketTimeoutException _))
    (if-not @result
      (recur result)
      @result)))

(defn assertions-watcher [conf pool assertions]
  (fn []
    (let [client (v/connect (:etcd conf))]
      (loop []
        (try
          (slate/load-assertions pool assertions)
          (log/info "refreshing assertions data" (watch-for-change client))
          (catch Exception _))
        (recur)))))

(defn start-assertions-watcher [conf pool assertions]
  (doto (Thread. (assertions-watcher conf pool assertions))
        .start))

(defn start-stream [conf pool]
  (let [assertions-watcher (assertions/start-watcher conf pool)
        deletions-watcher (deletions/start-deletion-watcher conf)
        pipeline (stream/pipeline (consumer/nsq-stream-producer (:nsq conf))
                                  (slate/slate-stage pool assertions/assertions)
                                  (hab/riemann-stage)
                                  (alerts/alert-stage pool conf))]
    (stream/start-pipeline! pipeline)))

(defn setup-jetty-server [^Server server]
  (.addBean server (doto (ErrorHandler.)
                         (.setShowStacks true))))

(defn start-server [args]
  (let [conf (config (last args))
        db (pool (:db-spec conf))]
    (start-stream conf db)
    (run-jetty (api/handler db conf) (assoc (:server conf) :configurator setup-jetty-server))))

(defn -main [& args]
  (let [cmd (first args)
        subargs (rest args)]
    (case cmd
      "server" (start-server subargs)
      "db" (migrate/db-cmd subargs))))
