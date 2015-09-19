(ns beavis.api
  (:require [clojure.tools.logging :as log]
            [cheshire.core :refer :all]
            [opsee.middleware.core :refer :all]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.params :refer [wrap-params]]
            [clojure.java.jdbc :refer [with-db-transaction db-set-rollback-only!]]
            [liberator.dev :refer [wrap-trace]]
            [liberator.core :refer [resource defresource]]
            [liberator.representation :refer [ring-response]]
            [schema.core :as s]
            [beavis.sql :as sql]
            [compojure.api.sweet :refer :all]))

;;;;;========== Globals (eat my ass) ======

(def db (atom nil))

;;;;;========== Resource Callbacks =========

(defn assertion-exists? [check-id]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)
          assertions (sql/get-assertions-by-check-and-customer @db {:check_id check-id :customer_id customer-id})]
      {:assertions {:check-id check-id
                    :assertions assertions}})))

(defn create-assertion [assertions]
  (fn [ctx]
    (with-db-transaction [tx @db]
      (let [login (:login ctx)
            customer-id (:customer_id login)
            check-id (:check-id assertions)
            inserts (for [assertion (:assertions assertions)
                          :let [record (assoc assertion :check_id check-id :customer_id customer-id)]]
                      (sql/insert-into-assertions! @db record))]
        (log/info inserts)
        {:assertions (if (not-any? not inserts)
                       assertions
                       (do
                         (db-set-rollback-only! tx)
                         (ring-response {:status 500
                                         :body (generate-string {:errors "A database error occurred."})})))}))))

(defn- parse-if-int [v]
  (if (re-matches #"\d+" v)
    (Integer/parseInt v)
    v))

(defn list-assertions [ctx]
  (let [login (:login ctx)
        customer-id (:customer_id login)]
    (vals (reduce (fn [acc rec]
                    (let [check-id (:check_id rec)
                          check-ass (-> (get acc check-id {:check-id check-id
                                                           :assertions []})
                                        (update :assertions conj
                                                (-> (apply dissoc rec (conj
                                                                        (for [[k v] rec :when (nil? v)] k)
                                                                        :id :check_id :customer_id))
                                                    (update :operand parse-if-int))))]
                      (assoc acc check-id check-ass)))
                  {}
                  (sql/get-assertions-by-customer @db customer-id)))))

;;;;;========== Resource Defs =========

(def defaults
  {:authorized? (authorized?)
   :available-media-types ["application/json"]
   :as-response (fn [data _] {:body data})})

(defresource assertions-resource [assertions] defaults
  :allowed-methods [:get :post]
  :post! (create-assertion assertions)
  :handle-created :assertions
  :handle-ok list-assertions)

;;;;;========== Schema Defs ============

(s/defschema Assertion
  {:key s/Str
   (s/optional-key :value) s/Str
   :relationship (s/enum "equal" "notEqual" "empty" "notEmpty" "contain" "notContain" "regExp")
   (s/optional-key :operand) s/Any})

(s/defschema CheckAssertions
  {:check-id   s/Str
   :assertions [Assertion]})

;;;;;============ Routes ===============

(defapi beavis-api
  {:exceptions {:exception-handler robustify-errors}}

  (routes
    (swagger-docs "/api/swagger.json")

    (swagger-ui "/api/swagger"
      :swagger-docs "/api/swagger.json")

    (GET* "/health_check" []
      :no-doc true
      "A ok")

    (POST* "/assertions" []
      :summary "Creates a new assertion to be run against a given check."
      :body [assertions CheckAssertions]
      :return CheckAssertions
      (assertions-resource assertions))

    (GET* "/assertions" []
      :summary "Retrieves all of a customer's assertions."
      :return [CheckAssertions]
      (assertions-resource nil))))

(defn handler [pool config]
  (reset! db pool)
  (-> beavis-api
      log-request
      log-response
      (wrap-cors :access-control-allow-origin [#"https?://localhost(:\d+)?"
                                               #"https?://opsee\.com"
                                               #"https?://opsee\.co"
                                               #"https?://opsy\.co"
                                               #"null"]
                 :access-control-allow-methods [:get :put :post :patch :delete])
      vary-origin
      wrap-params
      (wrap-trace :header :ui)))