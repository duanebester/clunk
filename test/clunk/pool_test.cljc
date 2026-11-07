(ns clunk.pool-test
  "Runs against the Postgres from docker-compose.yml, on the JVM and on Node."
  (:require #?(:clj [clojure.test :as t] :cljs [cljs.test :as t])
            [clojure.core.async :as async]
            [clunk.core :as clunk]
            [clunk.pool :as pool])
  #?(:clj (:import (java.util.concurrent CompletableFuture ExecutionException)
                   (java.util.function BiConsumer))))

(def config {:username "jimmy" :password "banana" :database "world"})

(defn- run
  "Runs a go block's channel to completion, failing the test after 10 s."
  [ch]
  (let [result (async/go (async/alt! ch ([v] v)
                                     (async/timeout 10000) (t/is false "timed out")))]
    #?(:clj (async/<!! result)
       :cljs (t/async done (async/take! result (fn [_] (done)))))))

(defn- settle
  "A channel that yields what a CompletableFuture or js/Promise settles to:
   its value, or its error."
  [p]
  (let [ch (async/promise-chan)]
    #?(:clj  (.whenComplete ^CompletableFuture p (reify BiConsumer (accept [_ v e] (async/put! ch (or e v)))))
       :cljs (.then p #(async/put! ch %) #(async/put! ch %)))
    ch))

(defn- exec
  "A channel that yields execute's rows, or its error."
  ([p sql] (settle (clunk/execute p sql)))
  ([p sql opts] (settle (clunk/execute p sql opts))))

(defn- now []
  #?(:clj (System/currentTimeMillis) :cljs (js/Date.now)))

(defn- pid
  "A channel that yields the backend pid of the connection that ran the query."
  [p]
  (async/go (:pid (first (async/<! (exec p "SELECT pg_backend_pid() AS pid"))))))

(t/deftest queries-run-in-parallel-on-up-to-max-size-connections
  (let [p (pool/pool (assoc config :max-size 3))]
    (run (async/go
           ;; One at a time, six half-second queries take 3 s; three at a time, 1 s.
           (let [start (now)
                 chs   (doall (repeatedly 6 #(exec p "SELECT pg_backend_pid() AS pid FROM pg_sleep(0.5)")))
                 rows  (loop [[ch & more] chs
                              acc         []]
                         (if ch (recur more (into acc (async/<! ch))) acc))
                 took  (- (now) start)]
             (t/is (= 6 (count rows)))
             (t/is (= 3 (count (set (map :pid rows)))))
             (t/is (<= 1000 took 1900) (str "took " took " ms")))
           (clunk/close p)))))

(t/deftest a-cancelled-query-gives-its-connection-back
  (let [p (pool/pool (assoc config :max-size 1))]
    (run (async/go
           (let [first-pid (async/<! (pid p))
                 token     (clunk/cancel-token)
                 ended     (async/promise-chan)
                 rows      (volatile! 0)]
             ;; A portal of batches of 10 rows, cancelled at its first row.
             (clunk/query p ["SELECT generate_series(1, $1)::int4 AS n" 100000]
                          (reify clunk/Sink
                            (-row! [_ _ _] (vswap! rows inc) (clunk/cancel! token) true)
                            (-flush! [_ _] true)
                            (-error! [_ _ err] (async/put! ended err) true)
                            (-end! [_ _] (async/put! ended :ended)))
                          {:cancel token :fetch-size 10})
             (t/is (= :ended (async/<! ended)))
             (t/is (<= 1 @rows 10))
             ;; The same connection, back in the pool, and it still works.
             (t/is (= first-pid (async/<! (pid p))))
             (t/is (= [{:n 3}] (async/<! (exec p ["SELECT $1::int4 + 1 AS n" 2])))))
           (clunk/close p)))))

(t/deftest a-query-cancelled-while-waiting-never-runs
  (let [p (pool/pool (assoc config :max-size 1))]
    (run (async/go
           (let [busy    (exec p "SELECT 1 AS n FROM pg_sleep(0.3)") ;; has the only connection
                 ;; Enough waiters that handing the client down the line one
                 ;; call inside another would overflow the stack.
                 tokens  (doall (repeatedly 20000 clunk/cancel-token))
                 waiters (mapv #(exec p "SET application_name = 'cancelled'" {:cancel %}) tokens)]
             (run! clunk/cancel! tokens)
             (t/is (= [{:n 1}] (async/<! busy)))
             (t/is (= {:cancelled true} (ex-data (async/<! (first waiters)))))
             (t/is (= {:cancelled true} (ex-data (async/<! (peek waiters)))))
             (t/is (= [{:application_name ""}] (async/<! (exec p "SHOW application_name")))))
           ;; Cancelled before it's made, and lent the idle connection at once.
           (let [result (async/<! (exec p "SET application_name = 'cancelled'" {:cancel (doto (clunk/cancel-token) clunk/cancel!)}))]
             (t/is (= {:cancelled true} (ex-data result)))
             (t/is (= [{:application_name ""}] (async/<! (exec p "SHOW application_name")))))
           (clunk/close p)))))

(t/deftest a-lost-connection-is-replaced
  (let [p     (pool/pool (assoc config :max-size 1))
        admin (clunk/client config)]
    (run (async/go
           (async/<! (settle (clunk/connect admin)))
           ;; Killed while idle in the pool.
           (let [old (async/<! (pid p))]
             (async/<! (exec admin (str "SELECT pg_terminate_backend(" old ")")))
             (async/<! (async/timeout 100))
             (let [replaced (async/<! (pid p))]
               (t/is (int? replaced))
               (t/is (not= old replaced))))
           ;; Killed during its own query.
           (let [old      (async/<! (pid p))
                 result   (async/<! (exec p "SELECT pg_terminate_backend(pg_backend_pid())"))
                 replaced (async/<! (pid p))]
             (t/is (clunk/error? result))
             (t/is (int? replaced))
             (t/is (not= old replaced)))
           (clunk/close admin)
           (clunk/close p)))))

(t/deftest connect-errors-and-a-closed-pool-fail-queries
  (let [bad (pool/pool (assoc config :password "wrong" :max-size 1))
        p   (pool/pool config)]
    (run (async/go
           ;; Each failed connect frees its slot, so the next query tries again.
           (dotimes [_ 2]
             (t/is (= "28P01" (:code (ex-data (async/<! (exec bad "SELECT 1")))))))
           (t/is (clunk/error? (async/<! (exec (pool/pool (assoc config :port 1)) "SELECT 1"))))
           ;; So does a config clunk refuses.
           (t/is (re-find #"Unknown :ssl mode" (ex-message (async/<! (exec (pool/pool (assoc config :ssl :verify)) "SELECT 1")))))
           (t/is (= [{:n 1}] (async/<! (exec p "SELECT 1::int4 AS n"))))
           (clunk/close p)
           (t/is (= "Pool is closed" (ex-message (async/<! (exec p "SELECT 1")))))
           (clunk/close bad)))))

#?(:clj
   (t/deftest execute-and-plan-take-a-pool
     (let [p (pool/pool (assoc config :max-size 1))]
       (t/is (= [{:n 1} {:n 2}] @(clunk/execute p "SELECT generate_series(1, 2)::int4 AS n")))
       (t/is (= 5050 (transduce (map :n) + 0 (clunk/plan p "SELECT generate_series(1, 100)::int4 AS n"))))
       ;; Leaving a reduce early, between portal batches, gives the only
       ;; connection back, and the next query on it works.
       (let [before (:pid (first @(clunk/execute p "SELECT pg_backend_pid() AS pid")))]
         (t/is (= [{:n 1} {:n 2} {:n 3}]
                  (into [] (take 3) (clunk/plan p ["SELECT generate_series(1, $1)::int4 AS n" 1000] {:fetch-size 10}))))
         (t/is (= [{:pid before :n 3}]
                  @(clunk/execute p ["SELECT pg_backend_pid() AS pid, $1::int4 + 1 AS n" 2]))))
       ;; Leaving a reduce early gives the only connection back.
       (t/is (= {:n 1} (reduce (fn [_ row] (Thread/sleep 200) (reduced row))
                               nil
                               (clunk/plan p "SELECT generate_series(1, 1000)::int4 AS n"))))
       (t/is (= [{:n 7}] (deref (clunk/execute p "SELECT 7::int4 AS n") 5000 :timed-out)))
       ;; A value that fails to decode (NaN has no BigDecimal) fails its query
       ;; but leaves the protocol intact, so the connection stays in use.
       (let [before (:pid (first @(clunk/execute p "SELECT pg_backend_pid() AS pid")))
             err    (try (deref (clunk/execute p "SELECT 'NaN'::numeric AS x") 5000 :timed-out)
                         (catch ExecutionException e (ex-cause e)))]
         (t/is (= 1700 (:oid (ex-data err))))
         (t/is (= [{:pid before}] (deref (clunk/execute p "SELECT pg_backend_pid() AS pid") 5000 :timed-out))))
       (clunk/close p))))
