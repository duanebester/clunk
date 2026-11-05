(ns clunk.node-test
  "The JavaScript API, against the Postgres from docker-compose.yml."
  (:require [cljs.test :as t]
            [cljs.core.async :as async]
            [cljs.core.async.interop :refer-macros [<p!]]
            [clunk.node :as node]
            ["stream" :refer [Readable]]))

(def config #js {:username "jimmy" :password "banana" :database "world"})

(defn- rejection
  "Inside a go block, parks until promise settles and returns what it rejected
   with, or nil if it resolved."
  [promise]
  (async/go (try (<p! promise) nil
                 (catch :default e (ex-cause e)))))

(defn- pg-fields [^js err]
  [(.-code err) (.-severity err) (.-sql err)])

(t/deftest query-stream-and-errors
  (t/async done
    (async/go
      (let [c (<p! (node/connect config))]
        (t/is (= [{"n" 1} {"n" 2}] (js->clj (<p! (node/query c "SELECT generate_series(1, 2)::int4 AS n")))))
        (let [err (async/<! (rejection (node/query c "SELECT * FROM no_such_table")))]
          (t/is (instance? js/Error err))
          (t/is (not (instance? ExceptionInfo err)))
          (t/is (= ["42P01" "ERROR" "SELECT * FROM no_such_table"] (pg-fields err))))
        (t/is (= [{"n" 1} {"n" 2} {"n" 3}]
                 (js->clj (<p! (.toArray (.from Readable (node/stream c "SELECT generate_series(1, 3)::int4 AS n")))))))
        (let [err (async/<! (rejection (.next (node/stream c "SELECT * FROM no_such_table"))))]
          (t/is (= "42P01" (.-code err))))
        ;; Leave a stream after one row, once the client has filled its row
        ;; buffer, as break or a closed HTTP response does. The next query must run.
        (let [it (node/stream c "SELECT generate_series(1, 1000)::int4 AS n")]
          (t/is (= {"n" 1} (js->clj (.-value (<p! (.next it))))))
          (async/<! (async/timeout 200))
          (<p! (.return it)))
        (let [[rows] (async/alts! [(async/go (<p! (node/query c "SELECT 7::int4 AS n")))
                                   (async/timeout 5000)])]
          (t/is (= [{"n" 7}] (js->clj rows))))
        (node/close c))
      (let [err (async/<! (rejection (node/connect (doto (js/Object.assign #js {} config) (aset "password" "wrong")))))]
        (t/is (instance? js/Error err))
        (t/is (= "28P01" (.-code err))))
      (done))))
