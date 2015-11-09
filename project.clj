(defproject beavis "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :main ^:skip-aot beavis.core
  :java-source-paths ["src"]
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[midje "1.6.3"]
                                  [clj-http-fake "1.0.1"]
                                  [ring/ring-mock "0.2.0"]]
                   ;:jvm-opts ["-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5006"]
                   :plugins [[lein-midje "3.0.0"]]}}
  :plugins [[s3-wagon-private "1.1.2"]]
  :repositories [["snapshots" {:url "s3p://opsee-maven-snapshots/snapshot"
                               :username :env
                               :passphrase :env}]
                 ["releases" {:url "s3p://opsee-maven-snapshots/releases"
                              :username :env
                              :passphrase :env}]]
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [io.nodyn/nodyn "0.1.1-SNAPSHOT"]
                 [com.fasterxml.jackson.core/jackson-core "2.2.3"]
                 [com.fasterxml.jackson.core/jackson-databind "2.2.3"]
                 [riemann "0.2.10" :exclusions [com.fasterxml.jackson.core/jackson-databind com.fasterxml.jackson.core/jackson-core]]
                 [co.opsee/opsee-middleware "0.1.7"]
                 [info.sunng/ring-jetty9-adapter "0.8.1"]
                 [com.github.brainlag/nsq-client "1.0.0-SNAPSHOT"]
                 [yesql "0.4.1-SNAPSHOT"]
                 [verschlimmbesserung "0.1.2"]
                 [org.postgresql/postgresql "9.4-1202-jdbc42"]
                 [instaparse "1.4.1"]
                 [com.cemerick/url "0.1.1"]
                 [clj-wallhack "1.0.1"]
                 [metosin/compojure-api "0.22.0" :exclusions [com.fasterxml.jackson.core/jackson-core com.fasterxml.jackson.core/jackson-databind]]
                 [gws/clj-mandrill "0.4.0"]])
