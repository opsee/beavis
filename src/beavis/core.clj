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
            [opsee.middleware.watcher :as watcher]
            [beavis.habilitationsschrift :as hab]
            [beavis.kundenbenachrichtigung :as alerts])
  (:import (org.eclipse.jetty.server Server)
           (org.eclipse.jetty.server.handler ErrorHandler)))

(defn start-stream [conf pool bartnet-pool]
  (let [assertions-watcher (watcher/start "assertions" (:etcd conf) (fn [_] (assertions/reload-assertions pool)) assertions/path)
        deletions-watcher (watcher/start "deletions" (:etcd conf) deletions/reload-deletes deletions/path {:recursive? true})
        pipeline (stream/pipeline (consumer/nsq-stream-producer (:nsq conf))
                                  (slate/slate-stage bartnet-pool assertions/assertions)
                                  (hab/riemann-stage)
                                  (alerts/alert-stage pool conf))]
    (stream/start-pipeline-async! pipeline)))

(defn setup-jetty-server [^Server server]
  (.addBean server (doto (ErrorHandler.)
                         (.setShowStacks true))))

(defn start-server [args]
  (let [conf (config (last args))
        db (pool (:db-spec conf))
        bartnet-db (pool (:bartnet-db-spec conf))]
    (start-stream conf db bartnet-db)
    (run-jetty (api/handler db bartnet-db conf) (assoc (:server conf) :configurator setup-jetty-server))))

(defn -main [& args]
  (let [cmd (first args)
        subargs (rest args)]
    (case cmd
      "server" (start-server subargs)
      "db" (migrate/db-cmd subargs))))
