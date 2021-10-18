(ns com.clunk.core_test
  #?(:clj
     (:require [com.clunk.core :as core]
               [clojure.test :as test])
     :cljs
     (:require [com.clunk.core :as core]
               [cljs.test :as test])))

(def messages '({:type :RowDescription, :num-cols 1, :values [{:field-name "name", :table-oid 16397, :column-attr 2, :oid 1043, :data-type-size -1, :type-modifier -1, :format-code 0}]}
                {:type :DataRow, :num-cols 1, :values [{:idx 0, :data [65, 102, 103, 104, 97, 110, 105, 115, 116, 97, 110]}]}
                {:type :Close, :close 83, :name "ELECT 1"}
                {:type :ReadyForQuery, :status 73}))

