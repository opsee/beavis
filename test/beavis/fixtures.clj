(ns beavis.fixtures
  (:require [beavis.sql :as sql]))



(defn assertion-fixtures [db]
  (sql/insert-into-assertions! db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                   :check_id "hello"
                                   :key "header"
                                   :value "content-type"
                                   :relationship "equal"
                                   :operand "text/plain"})
  (sql/insert-into-assertions! db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                   :check_id "hello"
                                   :key "statusCode"
                                   :relationship "equal"
                                   :operand "200"})
  (sql/insert-into-assertions! db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                   :check_id "check2"
                                   :key "header"
                                   :value "vary"
                                   :relationship "equal"
                                   :operand "origin"})
  (sql/insert-into-assertions! db {:customer_id "154ba57a-5188-11e5-8067-9b5f2d96dce1"
                                   :check_id "check2"
                                   :key "statusCode"
                                   :relationship "notEqual"
                                   :operand "500"}))