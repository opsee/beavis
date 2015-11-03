(ns beavis.api
  (:require [clojure.tools.logging :as log]
            [cheshire.core :refer :all]
            [opsee.middleware.core :refer :all]
            [opsee.middleware.protobuilder :as pb]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.params :refer [wrap-params]]
            [clojure.java.jdbc :refer [with-db-transaction db-set-rollback-only!]]
            [liberator.dev :refer [wrap-trace]]
            [liberator.core :refer [resource defresource]]
            [liberator.representation :refer [ring-response]]
            [schema.core :as s]
            [verschlimmbesserung.core :as v]
            [riemann.query :as query]
            [beavis.sql :as sql]
            [clojure.string :as str]
            [compojure.route :as rt]
            [compojure.api.sweet :refer :all]
            [opsee.middleware.protobuilder :as proto]
            [beavis.habilitationsschrift :as hab])
  (:import (co.opsee.proto Target CheckResult Check CheckResponse)))

;;;;;========== Globals (eat my ass) ======

(def db (atom nil))
(def etcd-client (atom nil))

;;;;;========== Resource Callbacks =========

(defn notify-assertions-reload []
  (try
    (v/reset! @etcd-client "/opsee.co/assertions" "reload")
    (catch Exception ex (log/error "" ex))))

(defn not-delete? [ctx]
  (not= (get-in ctx [:request :request-method]) :delete))

(defn- cust-id [ctx]
  (-> ctx
      :login
      :customer_id))

(defmulti parse-if-int (fn [v] (class v)))
(defmethod parse-if-int Integer [v] v)
(defmethod parse-if-int String [v]
  (if (re-matches #"\d+" v)
    (Integer/parseInt v)
    v))

(defn- key-process [record f]
  (into {} (map (fn [[k v]]
                  [(-> (name k)
                       f
                       keyword) v]))
        record))

(defn- operand-to-int [record]
  (if (:operand record)
    (update record :operand parse-if-int)
    record))

(defn- do-bulk-inserts [customer-id check-id records method tx]
  (let [inserts (for [rec records
                      :let [record (assoc rec :check_id check-id :customer_id customer-id)]]
                  (method tx record))]
    (if (not-any? not inserts)
      inserts
      (do
        (db-set-rollback-only! tx)
        (ring-response {:status 500
                        :body (generate-string {:errors "A database error occurred."})})))))

(defn- records->rollups [records field-name]
  (if (record? records)
    records
    (vals (reduce (fn [acc rec]
                    (let [check-id (:check_id rec)
                          check-ass (-> (get acc check-id {:check-id check-id
                                                           field-name []})
                                        (update field-name conj
                                                (-> (apply dissoc rec (conj
                                                                        (for [[k v] rec :when (nil? v)] k)
                                                                        :id :check_id :customer_id))
                                                    operand-to-int)))]
                      (assoc acc check-id check-ass)))
                  {}
                  records))))

(defn assertion-exists? [check-id]
  (fn [ctx]
    (let [check-assertion (-> (sql/get-assertions-by-check-and-customer @db {:check_id check-id
                                                                             :customer_id (cust-id ctx)})
                              (records->rollups :assertions)
                              first)]
      {:assertions check-assertion})))

(defn update-assertion! [check-id assertions]
  (fn [ctx]
    (with-db-transaction [tx @db]
      (sql/delete-assertions-by-check-and-customer! tx {:check_id check-id
                                                        :customer_id (cust-id ctx)})
      (let [asserts (first (records->rollups
                             (do-bulk-inserts (cust-id ctx)
                                              check-id
                                              (:assertions assertions)
                                              sql/insert-into-assertions<!
                                              tx)
                             :assertions))]
        #(assoc ctx :assertions asserts)))))

(defn delete-assertion! [check-id]
  (fn [ctx]
    (sql/delete-assertions-by-check-and-customer! @db {:check_id check-id
                                                       :customer_id (cust-id ctx)})))

(defn create-assertion! [assertions]
  (fn [ctx]
    (let [ret (with-db-transaction [tx @db]
                (let [check-id (:check-id assertions)]
                  {:assertions (first (records->rollups
                                        (do-bulk-inserts (cust-id ctx)
                                                         check-id
                                                         (:assertions assertions)
                                                         sql/insert-into-assertions<!
                                                         tx)
                                        :assertions))}))]
    (notify-assertions-reload)
    ret)))

(defn list-assertions [ctx]
  (records->rollups (sql/get-assertions-by-customer @db (cust-id ctx)) :assertions))

(defn notification-exists? [check-id]
  (fn [ctx]
    (let [check-notification (-> (sql/get-notifications-by-check-and-customer @db {:check_id check-id
                                                                                   :customer_id (cust-id ctx)})
                                 (records->rollups :notifications)
                                 first)]
      {:notifications check-notification})))

(defn update-notification! [check-id notifications]
  (fn [ctx]
    (with-db-transaction [tx @db]
      (sql/delete-notifications-by-check-and-customer! tx {:check_id check-id
                                                           :customer_id (cust-id ctx)})
      (let [notifs (first (records->rollups
                            (do-bulk-inserts (cust-id ctx)
                                             check-id
                                             (:notifications notifications)
                                             sql/insert-into-notifications<!
                                             tx)
                            :notifications))]
        #(assoc ctx :notifications notifs)))))

(defn delete-notification! [check-id]
  (fn [ctx]
    (sql/delete-notifications-by-check-and-customer! @db {:check_id check-id
                                                          :customer_id (cust-id ctx)})))

(defn create-notification! [notifications]
  (fn [ctx]
    (with-db-transaction [tx @db]
      (let [check-id (:check-id notifications)]
        {:notifications (first (records->rollups
                          (do-bulk-inserts (cust-id ctx)
                                           check-id
                                           (:notifications notifications)
                                           sql/insert-into-notifications<!
                                           tx)
                          :notifications))}))))

(defn list-notifications [ctx]
  (records->rollups (sql/get-notifications-by-customer @db (cust-id ctx)) :notifications))

(defn results-exist? [q]
  (fn [ctx]
    (let [customer-id (:customer_id (:login ctx))
          ast (query/ast q)
          results (filter #(= customer-id (:customer_id %)) (hab/query ast))]
      {:results results})))

;;;;;========== Resource Defs =========

(def defaults
  {:authorized? (authorized?)
   :available-media-types ["application/json"]
   :as-response (fn [data _] {:body data})})

(defresource assertions-resource [assertions] defaults
  :allowed-methods [:get :post]
  :post! (create-assertion! assertions)
  :handle-created :assertions
  :handle-ok list-assertions)

(defresource assertion-resource [check_id assertions] defaults
  :allowed-methods [:get :put :delete]
  :exists? (assertion-exists? check_id)
  :put! (update-assertion! check_id assertions)
  :delete! (delete-assertion! check_id)
  :new? false
  :respond-with-entity? not-delete?
  :handle-ok :assertions)

(defresource notifications-resource [notifications] defaults
  :allowed-methods [:get :post]
  :post! (create-notification! notifications)
  :handle-created :notifications
  :handle-ok list-notifications)

(defresource notification-resource [check_id notifications] defaults
  :allowed-methods [:get :put :delete]
  :exists? (notification-exists? check_id)
  :put! (update-notification! check_id notifications)
  :delete! (delete-notification! check_id)
  :new? false
  :respond-with-entity? not-delete?
  :handle-ok :notifications)

(defresource results-resource [q] defaults
  :allowed-methods [:get]
  :exists? (results-exist? q)
  :handle-ok :results)

;;;;;========== Schema Defs ============

(s/defschema Assertion
  {:key                      s/Str
   (s/optional-key :value)   s/Str
   :relationship             (s/enum "equal" "notEqual" "empty" "notEmpty" "contain" "notContain" "regExp")
   (s/optional-key :operand) (s/either s/Str s/Num)})

(s/defschema CheckAssertions
  {:check-id   s/Str
   :assertions [Assertion]})

(s/defschema Notification
  {:type  s/Str
   :value s/Str})

(s/defschema CheckNotifications
  {:check-id      s/Str
   :notifications [Notification]})

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

    (context* "/assertions" []
      :tags ["assertions"]

      (POST* "/" []
        :summary "Creates a new assertion to be run against a given check."
        :body [assertions CheckAssertions]
        :return CheckAssertions
        (assertions-resource assertions))

      (GET* "/" []
        :summary "Retrieves all of a customer's assertions."
        :return [CheckAssertions]
        (assertions-resource nil))

      (GET* "/:check_id" [check_id]
        :summary "Retrieves the assertions for a check."
        :return CheckAssertions
        (assertion-resource check_id nil))

      (DELETE* "/:check_id" [check_id]
        :summary "Deletes the assertions for a check."
        (assertion-resource check_id nil))

      (PUT* "/:check_id" [check_id]
        :summary "Replaces an assertion."
        :body [assertions CheckAssertions]
        :return CheckAssertions
        (assertion-resource check_id assertions)))

    (context* "/notifications" []
      :tags ["notifications"]

      (GET* "/" []
        :summary "Retrieve all of a customer's notifications"
        :return [CheckNotifications]
        (notifications-resource nil))

      (POST* "/" []
        :summary "Create a new notification."
        :body [notification CheckNotifications]
        :return CheckNotifications
        (notifications-resource notification))

      (GET* "/:check_id" [check_id]
        :summary "Retrieves a notification."
        :return CheckNotifications
        (notification-resource check_id nil))

      (DELETE* "/:check_id" [check_id]
        :summary "Deletes a notification."
        (notification-resource check_id nil))

      (PUT* "/:check_id" [check_id]
        :summary "Replaces a notification."
        :body [notifications CheckNotifications]
        :return CheckNotifications
        (notification-resource check_id notifications)))

    (context* "/results" []
      :tags ["results"]

      (GET* "/" []
        :summary "Retrieves check results."
        :query-params [q :- String]
        (results-resource q))))

  (rt/not-found "Not found."))

(defn handler [pool config]
  (reset! db pool)
  (reset! etcd-client (v/connect (:etcd config)))
  (-> beavis-api
      log-request
      log-response
      (wrap-cors :access-control-allow-origin [#"https?://localhost(:\d+)?"
                                               #"https?://(\w+\.)?opsee\.com"
                                               #"https?://(\w+\.)?opsee\.co"
                                               #"https?://(\w+\.)?opsy\.co"
                                               #"null"]
                 :access-control-allow-methods [:get :put :post :patch :delete])
      vary-origin
      wrap-params
      (wrap-trace :header :ui)))