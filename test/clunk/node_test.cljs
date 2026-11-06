(ns clunk.node-test
  "The JavaScript API, against the Postgres from docker-compose.yml."
  (:require [cljs.test :as t]
            [cljs.core.async :as async]
            [cljs.core.async.interop :refer-macros [<p!]]
            [clunk.node :as node]
            [clunk.oid-types :as oid-types]
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

(defn- decode-fields [^js err]
  [(.-message err) (.-column err) (.-oid err) (.-sql err)])

(t/deftest query-stream-and-errors
  (t/async done
    (async/go
      (let [c (<p! (node/connect config))]
        (t/is (= [{"n" 1} {"n" 2}] (js->clj (<p! (node/query c "SELECT generate_series(1, 2)::int4 AS n")))))
        (t/is (= [{"n" 7 "s" nil}] (js->clj (<p! (node/query c "SELECT $1::int4 AS n, $2::text AS s" #js [7 nil])))))
        (let [err (async/<! (rejection (node/query c "SELECT * FROM no_such_table")))]
          (t/is (instance? js/Error err))
          (t/is (not (instance? ExceptionInfo err)))
          (t/is (= ["42P01" "ERROR" "SELECT * FROM no_such_table"] (pg-fields err))))
        (t/is (= [{"n" 1} {"n" 2} {"n" 3}]
                 (js->clj (<p! (.toArray (.from Readable (node/stream c "SELECT generate_series(1, 3)::int4 AS n")))))))
        (let [err (async/<! (rejection (.next (node/stream c "SELECT * FROM no_such_table"))))]
          (t/is (= "42P01" (.-code err))))
        ;; Leave a stream after one row, once the client has filled its row
        ;; buffer, as break or a closed HTTP response does. The next query must
        ;; run, and the server must have stopped after the first batch of 1000
        ;; rows: nextval counts the rows it produced.
        (<p! (node/query c "CREATE TEMP SEQUENCE stream_rows"))
        (let [it (node/stream c "SELECT nextval('stream_rows')::int4 AS n FROM generate_series(1, $1)" #js [100000])]
          (t/is (= {"n" 1} (js->clj (.-value (<p! (.next it))))))
          (async/<! (async/timeout 200))
          (<p! (.return it)))
        (let [[rows] (async/alts! [(async/go (<p! (node/query c "SELECT last_value::int4 AS n FROM stream_rows")))
                                   (async/timeout 5000)])]
          (t/is (= [{"n" 1000}] (js->clj rows))))
        (node/close c))
      (let [err (async/<! (rejection (node/connect (doto (js/Object.assign #js {} config) (aset "password" "wrong")))))]
        (t/is (instance? js/Error err))
        (t/is (= "28P01" (.-code err))))
      (done))))

(t/deftest a-value-that-fails-to-decode-rejects-only-its-query
  (t/async done
    (async/go
      ;; No Node decoder throws on a real value, so make point, read as raw
      ;; bytes, throw as a parser does on a value it doesn't expect.
      (let [decoder oid-types/decoder
            p       (node/pool (js/Object.assign #js {:maxSize 1} config))
            pid     #(async/go (get (first (js->clj (<p! (node/query p "SELECT pg_backend_pid() AS pid")))) "pid"))
            before  (async/<! (pid))
            sql     "SELECT 1::int4 AS n, point(1, 2) AS p"]
        (set! oid-types/decoder #(if (= 600 %) (fn [_ _] (throw (js/Error. "bad point"))) (decoder %)))
        (let [err (async/<! (rejection (node/query p sql)))]
          (t/is (instance? js/Error err))
          (t/is (not (instance? ExceptionInfo err)))
          (t/is (= ["Could not decode column p (type 600): bad point" "p" 600 sql] (decode-fields err))))
        (let [sql "SELECT g AS n, CASE WHEN g = 3 THEN point(1, 2) END AS p FROM generate_series(1, 10) g"
              it  (node/stream p sql)]
          (t/is (= [{"n" 1 "p" nil} {"n" 2 "p" nil}]
                   [(js->clj (.-value (<p! (.next it)))) (js->clj (.-value (<p! (.next it))))]))
          (t/is (= ["Could not decode column p (type 600): bad point" "p" 600 sql]
                   (decode-fields (async/<! (rejection (.next it)))))))
        ;; The pool's only connection is still in use.
        (t/is (= before (async/<! (pid))))
        (set! oid-types/decoder decoder)
        (node/close p))
      (done))))

(t/deftest a-pool-takes-the-place-of-a-client
  (t/async done
    (async/go
      (let [p (node/pool (js/Object.assign #js {:maxSize 1} config))]
        (t/is (= [{"n" 1} {"n" 2}] (js->clj (<p! (node/query p "SELECT generate_series(1, 2)::int4 AS n")))))
        ;; Leaving a stream between portal batches gives the only connection
        ;; back, and the next query on it works.
        (let [before (get (first (js->clj (<p! (node/query p "SELECT pg_backend_pid() AS pid")))) "pid")
              it     (node/stream p "SELECT generate_series(1, $1)::int4 AS n" #js [5000])]
          (t/is (= {"n" 1} (js->clj (.-value (<p! (.next it))))))
          (async/<! (async/timeout 200))
          (<p! (.return it))
          (let [[rows] (async/alts! [(async/go (<p! (node/query p "SELECT pg_backend_pid() AS pid, $1::int4 + 1 AS n" #js [6])))
                                     (async/timeout 5000)])]
            (t/is (= [{"pid" before "n" 7}] (js->clj rows)))))
        (node/close p)
        (t/is (= "Pool is closed" (.-message (async/<! (rejection (node/query p "SELECT 1")))))))
      (done))))
