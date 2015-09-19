(ns beavis.sql
  (:require [yesql.core :refer [defqueries]]))

(defqueries "queries.sql")