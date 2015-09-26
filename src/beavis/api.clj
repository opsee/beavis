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
            [clojure.string :as str]
            [compojure.api.sweet :refer :all]))

;;;;;========== Globals (eat my ass) ======

(def db (atom nil))

;;;;;========== Resource Callbacks =========

(defn- cust-id [ctx]
  (-> ctx
      :login
      :customer_id))

(defn- parse-if-int [v]
  (if (re-matches #"\d+" v)
    (Integer/parseInt v)
    v))

(defn- key-process [record f]
  (into {} (map (fn [[k v]]
                  [(-> (name k)
                       f
                       keyword) v]))
        record))

(defn- lift-underscores [record]
  (key-process record #(str/replace % #"_" "-")))

(defn- lower-dashes [record]
  (key-process record #(str/replace % #"\-" "_")))

(defn- clean-notification [record]
  (-> record
      (dissoc :customer_id)
      lift-underscores))

(defn records->check-assertions [records]
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
                records)))

(defn assertion-exists? [check-id]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)
          check-assertion (-> (sql/get-assertions-by-check-and-customer @db {:check_id check-id
                                                                             :customer_id customer-id})
                              records->check-assertions
                              first)]
      {:assertions check-assertion})))

(defn- do-assertion-inserts [customer-id check-id assertions tx]
  (let [inserts (for [assertion (:assertions assertions)
                      :let [record (assoc assertion :check_id check-id :customer_id customer-id)]]
                  (sql/insert-into-assertions! tx record))]
    {:assertions (if (not-any? not inserts)
                   assertions
                   (do
                     (db-set-rollback-only! tx)
                     (ring-response {:status 500
                                     :body (generate-string {:errors "A database error occurred."})})))}))

(defn update-assertion! [check-id assertions]
  (fn [ctx]
    (with-db-transaction [tx @db]
      (let [login (:login ctx)
            customer-id (:customer_id login)]
        (sql/delete-assertion-by-check-and-customer! tx {:check_id check-id
                                                         :customer_id customer-id})
        (do-assertion-inserts customer-id check-id assertions tx)))))

(defn delete-assertion! [check-id]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)]
      (sql/delete-assertion-by-check-and-customer! @db {:check_id check-id
                                                        :customer_id customer-id}))))

(defn create-assertion [assertions]
  (fn [ctx]
    (with-db-transaction [tx @db]
      (let [login (:login ctx)
            customer-id (:customer_id login)
            check-id (:check-id assertions)]
        (do-assertion-inserts customer-id check-id assertions tx)))))

(defn list-assertions [ctx]
  (let [login (:login ctx)
        customer-id (:customer_id login)]
    (records->check-assertions (sql/get-assertions-by-customer @db customer-id))))

(defn notification-exists? [id]
  (fn [ctx]
    (let [login (:login ctx)
          customer-id (:customer_id login)]
      {:notification (-> (sql/get-notification-by-customer-and-id @db {:customer_id customer-id
                                                                       :id id})
                         first
                         clean-notification)})))

(defn create-notification [notification]
  (fn [ctx]
    {:notification (-> (sql/insert-into-notifications<! @db (-> notification
                                                                (assoc :customer-id (cust-id ctx))
                                                                lower-dashes))
                       (dissoc :customer_id)
                       lift-underscores)}))

(defn delete-notification! [id]
  (fn [ctx]
    (sql/delete-notification-by-customer-and-id! @db {:id id
                                                      :customer_id (cust-id ctx)})))

(defn list-notifications [check-id]
  (fn [ctx]
    (map clean-notification
         (if check-id
           (sql/get-notifications-by-customer-and-check-id @db {:customer_id (cust-id ctx)
                                                                :check_id check-id})
           (sql/get-notifications-by-customer @db (cust-id ctx))))))

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

(defresource assertion-resource [check_id assertions] defaults
  :allowed-methods [:get :put :delete]
  :exists? (assertion-exists? check_id)
  :put! (update-assertion! check_id assertions)
  :delete! (delete-assertion! check_id)
  :new? false
  :handle-ok :assertions)

(defresource notifications-resource [notification check-id] defaults
  :allowed-methods [:get :post]
  :post! (create-notification notification)
  :handle-created :notification
  :handle-ok (list-notifications check-id))

(defresource notification-resource [id notification] defaults
  :allowed-methods [:get :delete]
  :exists? (notification-exists? id)
  :delete! (delete-notification! id)
  :new? false
  :handle-ok :notification)

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
  {(s/optional-key :id) s/Num
   :check-id            s/Str
   :type                s/Str
   :value               s/Str})

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
      (assertions-resource nil))

    (GET* "/assertions/:check_id" [check_id]
      :summary "Retrieves the assertions for a check."
      :return CheckAssertions
      (assertion-resource check_id nil))

    (DELETE* "/assertions/:check_id" [check_id]
      :summary "Deletes the assertions for a check."
      (assertion-resource check_id nil))

    (PUT* "/assertions/:check_id" [check_id]
      :summary "Replaces an assertion."
      :body [assertions CheckAssertions]
      :return CheckAssertions
      (assertion-resource check_id assertions))

    (GET* "/notifications/:id" []
      :summary "Retrieves a notification."
      :path-params [id :- Long]
      :return Notification
      (notification-resource id nil))

    (DELETE* "/notifications/:id" []
      :summary "Deletes a notification."
      :path-params [id :- Long]
      (notification-resource id nil))

    (GET* "/notifications" []
      :summary "Gets a filtered list of notifications."
      :query-params [{check-id :- s/Str nil}]
      :return [Notification]
      (notifications-resource nil check-id))

    (POST* "/notifications" []
      :summary "Create a new notification."
      :body [notification Notification]
      :return Notification
      (notifications-resource notification nil))))

(defn handler [pool config]
  (reset! db pool)
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