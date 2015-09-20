(defproject beavis "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :main ^:skip-aot beavis.core
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[midje "1.6.3"]
                                  [clj-http-fake "1.0.1"]
                                  [ring/ring-mock "0.2.0"]]
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
                 [riemann "0.2.10"]
                 [co.opsee/opsee-middleware "0.1.0-SNAPSHOT"]
                 [com.github.brainlag/nsq-client "1.0.0-SNAPSHOT"]
                 [yesql "0.4.1-SNAPSHOT"]
                 [org.postgresql/postgresql "9.4-1202-jdbc42"]
                 [instaparse "1.4.1"]
                 [metosin/compojure-api "0.22.0"]])
