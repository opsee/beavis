(ns beavis.t-api
  (:use midje.sweet
        opsee.middleware.test-helpers)
  (:require [beavis.api :as api]
            [clojure.tools.logging :as log]
            [opsee.middleware.auth :as auth]
            [cheshire.core :refer :all]
            [ring.mock.request :as mock]
            [beavis.sql :as sql]
            [beavis.fixtures :refer :all]
            [opsee.middleware.config :refer [config]]
            [opsee.middleware.core :refer [slurp-bytes]]))

; this token is for {"id":8,"customer_id":"154ba57a-5188-11e5-8067-9b5f2d96dce1","email":"cliff@leaninto.it","name":"cliff","verified":true,"admin":false,"active":true}
; it will expire in 10 yrs. hopefully that is long enough so that computers won't exist anymore
(def auth-header "Bearer eyJhbGciOiJBMTI4R0NNS1ciLCJlbmMiOiJBMTI4R0NNIiwiaXYiOiJXQWlLQ2Z1azk3TlBzM1ZYIiwidGFnIjoiaU56RG1LdjloQmE0TS1YU19YcEpPZyJ9.HqXl4bq3k3E9GQ7FtsWHaQ.SONY24NgxzEZk7c3.yYd7WZX3O8ChDIVFlG--kLr_bDfkNXcR7eAnCyZ-QhFKmlbKGKE9A1-uudKRPuZ05LEAxolOrZ0lPRkW7CM3jdEdYBcUITinztgz-POIdMOXdUjFODpNOVxlcHKtZo2JH1wNdzEobBtAmVbdkl2aNUJMhVSKWbsLV3efvKQ-wVfO3kHDNmYHJlp2DKh0-8yul4UcoDytkEDOfTrpGlZrxStXRNhSf0KhRK11fh3dXvyzj07OEdYuNVbqhtfyycBPUQUJnP1xDZTpDtZ3n7lJaA.OGbujXobjndTRus8wmCqIg")

(def defaults {"DB_NAME" "beavis_test",
               "DB_HOST" "localhost",
               "DB_PORT" "5432",
               "DB_USER" "postgres",
               "DB_PASS" ""})
(def test-config (config "resources/test-config.json" defaults))

(defn do-setup []
  (do
    (log/info test-config defaults)
    (start-connection test-config)))

(defn app []
  (do
    (log/info "start server")
    (auth/set-secret! (slurp-bytes (:secret test-config)))
    (api/handler @db test-config)))

(facts "assertions endpoint"
  (with-state-changes
    [(before :facts (doto
                      (do-setup)
                      assertion-fixtures))]
    (fact "gets all assertions"
      (let [response ((app) (-> (mock/request :get "/assertions")
                                (mock/header "Authorization" auth-header)))]
        (:status response) => 200
        (:body response) => (is-json (contains [(just {:check-id   "hello"
                                                       :assertions (contains [(just {:key          "header"
                                                                                     :value        "content-type"
                                                                                     :relationship "equal"
                                                                                     :operand      "text/plain"})
                                                                              (just {:key          "statusCode"
                                                                                     :relationship "equal"
                                                                                     :operand      200})] :in-any-order)})
                                                (just {:check-id   "check2"
                                                       :assertions (contains [(just {:key          "header"
                                                                                     :value        "vary"
                                                                                     :relationship "equal"
                                                                                     :operand      "origin"})
                                                                              (just {:key          "statusCode"
                                                                                     :relationship "notEqual"
                                                                                     :operand      500})] :in-any-order)})] :in-any-order))))
    (fact "posts new assertions"
      (let [response ((app) (-> (mock/request :post "/assertions" (generate-string {:check-id   "abc123"
                                                                                    :assertions [{:key          "statusCode"
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
                                            :assertions (just [(just {:key          "statusCode"
                                                                      :relationship "equal"
                                                                      :operand      200})
                                                               (just {:key          "header"
                                                                      :value        "content-type"
                                                                      :relationship "notEqual"
                                                                      :operand      "application/json"})]
                                                              :in-any-order)}))
        (sql/get-assertions-by-check @db "abc123") => (contains [(contains {:key          "statusCode"
                                                                            :relationship "equal"
                                                                            :operand      "200"})
                                                                 (contains {:key          "header"
                                                                            :value        "content-type"
                                                                            :relationship "notEqual"
                                                                            :operand      "application/json"})]
                                                                :in-any-order)))))

(facts "assertion endpoint"
  (with-state-changes
    [(before :facts (doto
                      (do-setup)
                      assertion-fixtures))]
    (fact "gets an assertion"
      (let [response ((app) (-> (mock/request :get "/assertions/hello")
                                (mock/header "Authorization" auth-header)))]
        (:status response) => 200
        (:body response) => (is-json (just {:check-id "hello"
                                            :assertions (contains [(just {:key "header"
                                                                          :value "content-type"
                                                                          :relationship "equal"
                                                                          :operand "text/plain"})
                                                                   (just {:key "statusCode"
                                                                          :relationship "equal"
                                                                          :operand 200})]
                                                                  :in-any-order)}))))
    (fact "deletes an assertion"
      (let [response ((app) (-> (mock/request :delete "/assertions/hello")
                                (mock/header "Authorization" auth-header)))]
        (:status response) => 204
        (sql/get-assertions-by-check @db "hello") => empty?))
    (fact "replaces an assertion"
      (let [response ((app) (-> (mock/request :put "/assertions/hello" (generate-string {:check-id "hello"
                                                                                         :assertions [{:key          "statusCode"
                                                                                                       :relationship "notEqual"
                                                                                                       :operand      500}]}))
                                (mock/header "Authorization" auth-header)))]
        (:status response) => 200
        (:body response) => (is-json (just {:check-id "hello"
                                            :assertions (just [(contains {:relationship "notEqual"
                                                                          :operand 500})])}))
        (sql/get-assertions-by-check @db "hello") => (just [(contains {:key          "statusCode"
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
        (:body response) => (is-json (just [(just {:check-id      "hello"
                                                   :notifications (just [(just {:type "email"
                                                                                :value "cliff@leaninto.it"})
                                                                         (just {:type "slack"
                                                                                :value "https://slack.com/fuckoff"})]
                                                                        :in-any-order)})
                                            (just {:check-id      "poop"
                                                   :notifications (just [(just {:type "email"
                                                                                :value "poooooooooooo"})])})]
                                           :in-any-order))))))