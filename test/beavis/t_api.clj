(ns beavis.t-api
  (:use midje.sweet
        opsee.middleware.test-helpers)
  (:require [beavis.api :as api]
            [clojure.tools.logging :as log]
            [opsee.middleware.auth :as auth]
            [cheshire.core :refer :all]
            [ring.mock.request :as mock]
            [beavis.sql :as sql]
            [beavis.stream :as s]
            [opsee.middleware.protobuilder :refer :all]
            [beavis.fixtures :refer :all]
            [cemerick.url :refer [url-encode]]
            [riemann.config :refer [core]]
            [beavis.habilitationsschrift :as hab]
            [opsee.middleware.config :refer [config]]
            [opsee.middleware.core :refer [slurp-bytes]]
            [opsee.middleware.migrate :refer [migrate-db]]
            [opsee.middleware.pool :refer [pool]]
            [beavis.habilitationsschrift :as hab]))

; this token is for {"id":8,"customer_id":"154ba57a-5188-11e5-8067-9b5f2d96dce1","email":"cliff@leaninto.it","name":"cliff","verified":true,"admin":false,"active":true}
; it will expire in 10 yrs. hopefully that is long enough so that computers won't exist anymore
(def auth-header (str "Basic eyJhY3RpdmUiOnRydWUsImlkIjo4LCJlbWFpbCI6ImNsaWZmQGxlYW5pbnRvLml0IiwidmVyaWZpZWQiOnRydWUsImN1c"
                      "3RvbWVyX2lkIjoiMTU0YmE1N2EtNTE4OC0xMWU1LTgwNjctOWI1ZjJkOTZkY2UxIiwiZXhwIjoxNzU2NzgwOTQxLCJzdW"
                      "IiOiJjbGlmZkBsZWFuaW50by5pdCIsImlhdCI6MTQ0MTIxMjE0MSwibmFtZSI6ImNsaWZmIiwiYWRtaW4iOmZhbHNlfQ=="))

(def defaults {"DB_NAME" "beavis_test",
               "DB_HOST" "beavis_dbhost",
               "DB_PORT" "5432",
               "DB_USER" "postgres"
               "DB_PASS" ""})
(def test-config (config "resources/test-config.json" defaults))
(def bartnet-db (atom nil))

(defn do-setup []
  (do
    (log/info test-config defaults)
    ;; It's okay if we have two test databases that are identical. We only
    ;; use the assertions table in this one but the schema are mostly the
    ;; same.
    (when-not @bartnet-db 
      (reset! bartnet-db (pool (:bartnet-db-spec test-config)))
      (log/info "bartnet-db is: " @bartnet-db)
      (migrate-db @bartnet-db {:drop-all true :silent true}))
    (start-connection test-config)))

(defn app []
  (do
    (log/info "start server")
    (api/handler @db @bartnet-db test-config)))

(facts "assertions endpoint"
  (with-state-changes
    [(before :facts (do
                      (do-setup) 
                      (assertion-fixtures @bartnet-db)))]
    (fact "gets all assertions"
      (let [response ((app) (-> (mock/request :get "/assertions")
                                (mock/header "Authorization" auth-header)))]
        (:status response) => 200
        (:body response) => (is-json (contains [(just {:check-id   "hello"
                                                       :assertions (contains [(just {:key          "header"
                                                                                     :value        "content-type"
                                                                                     :relationship "equal"
                                                                                     :operand      "text/plain"})
                                                                              (just {:key          "code"
                                                                                     :relationship "equal"
                                                                                     :operand      200})] :in-any-order)})
                                                (just {:check-id   "check2"
                                                       :assertions (contains [(just {:key          "body"
                                                                                     :relationship "contain"
                                                                                     :operand      "OK"})
                                                                              (just {:key          "code"
                                                                                     :relationship "notEqual"
                                                                                     :operand      500})] :in-any-order)})] :in-any-order))))
    (fact "posts new assertions"
      (let [response ((app) (-> (mock/request :post "/assertions" (generate-string {:check-id   "abc123"
                                                                                    :assertions [{:key          "code"
                                                                                                  :relationship "equal"
                                                                                                  :operand      200}
                                                                                                 {:key          "header"
                                                                                                  :value        "content-type"
                                                                                                  :relationship "notEqual"
                                                                                                  :operand      "application/json"}]}))
                                (mock/header "Authorization" auth-header)
                                (mock/header "Content-Type" "application/json")))]
        (:status response) => 201
        (:body response) => (is-json (just {:check-id   "abc123"
                                            :assertions (just [(just {:key          "code"
                                                                      :relationship "equal"
                                                                      :operand      200})
                                                               (just {:key          "header"
                                                                      :value        "content-type"
                                                                      :relationship "notEqual"
                                                                      :operand      "application/json"})]
                                                              :in-any-order)}))
        (sql/get-assertions-by-check @bartnet-db "abc123") => (contains [(contains {:key          "code"
                                                                            :relationship "equal"
                                                                            :operand      "200"})
                                                                 (contains {:key          "header"
                                                                            :value        "content-type"
                                                                            :relationship "notEqual"
                                                                            :operand      "application/json"})]
                                                                :in-any-order)))))

(facts "assertion endpoint"
  (with-state-changes
    [(before :facts (do
                      (do-setup)
                      (assertion-fixtures @bartnet-db)))]
    (fact "gets an assertion"
      (let [response ((app) (-> (mock/request :get "/assertions/hello")
                                (mock/header "Authorization" auth-header)))]
        (:status response) => 200
        (:body response) => (is-json (just {:check-id "hello"
                                            :assertions (contains [(just {:key "header"
                                                                          :value "content-type"
                                                                          :relationship "equal"
                                                                          :operand "text/plain"})
                                                                   (just {:key "code"
                                                                          :relationship "equal"
                                                                          :operand 200})]
                                                                  :in-any-order)}))))
    (fact "deletes an assertion"
      (let [response ((app) (-> (mock/request :delete "/assertions/hello")
                                (mock/header "Authorization" auth-header)))]
        (:status response) => 204
        (sql/get-assertions-by-check @bartnet-db "hello") => empty?))
    (fact "replaces an assertion"
      (let [response ((app) (-> (mock/request :put "/assertions/hello" (generate-string {:check-id "hello"
                                                                                         :assertions [{:key          "code"
                                                                                                       :relationship "notEqual"
                                                                                                       :operand      500}]}))
                                (mock/header "Authorization" auth-header)))]
        (:status response) => 200
        (:body response) => (is-json (just {:check-id "hello"
                                            :assertions (just [(contains {:relationship "notEqual"
                                                                          :operand 500})])}))
        (sql/get-assertions-by-check @bartnet-db "hello") => (just [(contains {:key          "code"
                                                                       :relationship "notEqual"
                                                                       :operand      "500"})])))))

(facts "notification endpoint"
  (with-state-changes
    [(before :facts (doto
                      (do-setup)
                      notification-fixtures))]
    (fact "gets a notification"
      (let [response ((app) (-> (mock/request :get "/notifications/hello")
                                (mock/header "Authorization" auth-header)))]
        (:status response) => 200
        (:body response) => (is-json (just {:check-id "hello"
                                            :notifications (contains [(just {:type "email"
                                                                             :value "cliff@leaninto.it"})
                                                                      (just {:type "slack"
                                                                             :value "https://slack.com/fuckoff"})]
                                                                     :in-any-order)}))))
    (fact "deletes a notification"
      (let [response ((app) (-> (mock/request :delete "/notifications/hello")
                                (mock/header "Authorization" auth-header)))]
        (:status response) => 204
        (sql/get-notifications-by-check @db "hello") => empty?))
    (fact "replace a notification"
      (let [response ((app) (-> (mock/request :put "/notifications/hello" (generate-string {:check-id "hello"
                                                                                           :notifications [{:type "email"
                                                                                                            :value "greg@opsee.co"}]}))
                                (mock/header "Authorization" auth-header)))]
        (:status response) => 200
        (:body response) => (is-json (just {:check-id "hello"
                                            :notifications (just [(contains {:value "greg@opsee.co"})])}))
        (sql/get-notifications-by-check @db "hello") => (just [(contains {:value "greg@opsee.co"})])))))

(facts "notifications endpoint"
  (with-state-changes
    [(before :facts (doto
                      (do-setup)
                      notification-fixtures))]
    (fact "posts a notification"
      (let [response ((app) (-> (mock/request :post "/notifications" (generate-string {:check-id "what"
                                                                                       :notifications [{:type "email"
                                                                                                        :value "sup@hotmail.net"}]}))
                                (mock/header "Authorization" auth-header)))]
        (:status response) => 201
        (:body response) => (is-json (just {:check-id "what"
                                            :notifications (just [(just {:type "email"
                                                                         :value "sup@hotmail.net"})])}))))
    (fact "gets all notifications"
      (let [response ((app) (-> (mock/request :get "/notifications")
                                (mock/header "Authorization" auth-header)))]
        (:status response) => 200
        (:body response) => (is-json (contains [(just {:check-id      "hello"
                                                       :notifications (just [(just {:type "email"
                                                                                    :value "cliff@leaninto.it"})
                                                                             (just {:type "slack"
                                                                                    :value "https://slack.com/fuckoff"})]
                                                                            :in-any-order)})
                                                (just {:check-id      "poop"
                                                       :notifications (just [(just {:type "email"
                                                                                    :value "poooooooooooo"})])})]
                                               :in-any-order))))))

(defn noop [_])

(def stage (atom nil))

(facts "results endpoint"
    (with-state-changes
      [(before :facts (do
                        (assertion-fixtures @bartnet-db)
                        (doto
                          (do-setup)
                          notification-fixtures
                          alert-fixtures)
                        (reset! stage (hab/riemann-stage))
                        (s/start-stage! @stage noop)
                        (reset-index)
                        (doseq [r [(check-result "154ba57a-5188-11e5-8067-9b5f2d96dce1" "check1" 3 3 0)
                                   (check-result "154ba57a-5188-11e5-8067-9b5f2d96dce1" "check2" 5 2 0)
                                   (check-result "derp" "check3" 3 3 0)]]
                          (s/submit @stage r))))
       (after :facts (do
                       (s/stop-stage! @stage)
                       (reset! stage nil)))]
      (fact "returns results for groups"
        (let [response ((app) (-> (mock/request :get (str "/results?q=" (url-encode "host = \"sg-sgsgsg\"")))
                                  (mock/header "Authorization" auth-header)))]
          (:status response) => 200
          (:body response) => (is-json (just [(contains {:check_id "check1"
                                                         :responses not-empty})
                                              (contains {:check_id "check2"
                                                         :responses not-empty})] :in-any-order))))
      (fact "returns results for checks"
        (let [response ((app) (-> (mock/request :get (str "/results?q=" (url-encode "service = \"check1\"")))
                                  (mock/header "Authorization" auth-header)))]
          (:status response) => 200
          (:body response) => (is-json (just [(contains {:check_id "check1"
                                                         :responses not-empty})
                                              (contains {:host "i-00000000"})
                                              (contains {:host "i-11111111"})
                                              (contains {:host "i-22222222"})] :in-any-order))))
      (fact "returns results for instances"
        (let [response ((app) (-> (mock/request :get (str "/results?q=" (url-encode "host = \"i-11111111\"")))
                                  (mock/header "Authorization" auth-header)))]
          (:status response) => 200
          (:body response) => (is-json (just [(contains {:host "i-11111111"})
                                              (contains {:host "i-11111111"})] :in-any-order))))
      (fact "gets all instance responses for a check"
        (let [response ((app) (-> (mock/request :get (str "/results?q=" (url-encode "service = \"check1\" and type = \"response\"")))
                                  (mock/header "Authorization" auth-header)))]
          (:status response) => 200
          (:body response) => (is-json (just [(contains {:host "i-00000000"})
                                              (contains {:host "i-11111111"})
                                              (contains {:host "i-22222222"})] :in-any-order))))
      (fact "deletes everything for a check"
        (let [response ((app) (-> (mock/request :delete "/results/check1")
                                  (mock/header "Authorization" auth-header)))]
          (:status response) => 204
          (sql/get-notifications-by-check @db "check1") => empty?
          (sql/get-assertions-by-check @bartnet-db "check1") => empty?
          (sql/get-alerts-by-check @db "check1") => empty?))))
