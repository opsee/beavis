(ns beavis.core
  (:gen-class)
  (:import (io.nodyn.runtime RuntimeFactory RuntimeFactory$RuntimeType NodynConfig)
           (io.nodyn NoOpExitHandler)))



(defn -main [& args]
  (let [factory (RuntimeFactory/init (-> (Object.) .getClass .getClassLoader) RuntimeFactory$RuntimeType/DYNJS)
        config (NodynConfig. (into-array ["-e" (str "var slate = require('./js/slate/index');"
                                                    "var example = require('./js/slate/src/example');"
                                                    "var result = slate.testCheck(example);"
                                                    "console.log(result);")]))
        nodyn (.newRuntime factory config)]
    (.setExitHandler nodyn (NoOpExitHandler.))
    (.run nodyn)))
