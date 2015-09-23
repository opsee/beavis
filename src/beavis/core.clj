(ns beavis.core
  (:gen-class)
  (:require [beavis.api :as api]
            [clojure.tools.logging :as log]
            [opsee.middleware.migrate :as migrate]
            [opsee.middleware.config :refer [config]]
            [opsee.middleware.pool :refer [pool]]
            [ring.adapter.jetty :refer [run-jetty]])
  (:import (io.nodyn.runtime RuntimeFactory RuntimeFactory$RuntimeType NodynConfig)
           (io.nodyn NoOpExitHandler)))


(defn start-server [args]
  (let [conf (config (last args))
        db (pool (:db-spec conf))]
    (run-jetty (api/handler db conf) (:server conf))))

(defn -main [& args]
  (let [cmd (first args)
        subargs (rest args)]
    (case cmd
      "server" (start-server subargs)
      "db" (migrate/db-cmd subargs))))

(defn derp []
  (let [factory (RuntimeFactory/init (-> (Object.) .getClass .getClassLoader) RuntimeFactory$RuntimeType/DYNJS)
        config (NodynConfig. (into-array ["-e" (str "var slate = require('./js/slate/index');"
                                                 "var example = require('./js/slate/src/example');"
                                                 "var result = slate.testCheck(example);"
                                                 "console.log(result);")]))
        nodyn (.newRuntime factory config)]
    (.setExitHandler nodyn (NoOpExitHandler.))
    (.run nodyn)))
