(ns beavis.alerts.t-email
  (:use midje.sweet
        opsee.middleware.test-helpers)
  (:require [beavis.alerts.email :as email]))

(facts "hash->merge-vars"
       (fact "returns an array of hashes formatted for the Mandrill API"
             (let [hash (email/hash->merge-vars {
                                             :key1 (map str [1 2 3])
                                             :key2 "string"
                                             :key3 {:key "value"}
                                             })]
               (every? :name hash) => true
               (every? :content hash) => true
               (map :name hash) => [:key1 :key2 :key3]
               (map :content hash) => ['("1" "2" "3") "string" {:key "value"}]
               (count hash) => 3)))
