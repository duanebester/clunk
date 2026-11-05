(ns clunk.pool-test
  "Runs against the Postgres from docker-compose.yml, on the JVM and on Node."
  (:require #?(:clj [clojure.test :as t] :cljs [cljs.test :as t])
            [clojure.core.async :as async]
            [clunk.core :as clunk]
            [clunk.pool :as pool]))

(def config {:username "jimmy" :password "banana" :database "world"})

(defn- run
  "Runs a go block's channel to completion, failing the test after 10 s."
  [ch]
  (let [result (async/go (async/alt! ch ([v] v)
                                     (async/timeout 10000) (t/is false "timed out")))]
    #?(:clj (async/<!! result)
       :cljs (t/async done (async/take! result (fn [_] (done)))))))

(defn- now []
  #?(:clj (System/currentTimeMillis) :cljs (js/Date.now)))

(defn- pid
  "A channel that yields the backend pid of the connection that ran the query."
  [p]
  (async/go (:pid (first (clunk/<query! p "SELECT pg_backend_pid() AS pid")))))

(defn- pending?
  "Inside a go block, parks 300 ms and returns true if ch yielded nothing."
  [ch]
  (async/go (let [[_ port] (async/alts! [ch (async/timeout 300)])]
              (not= ch port))))

(t/deftest queries-run-in-parallel-on-up-to-max-size-connections
  (let [p (pool/pool (assoc config :max-size 3))]
    (run (async/go
           ;; One at a time, six half-second queries take 3 s; three at a time, 1 s.
           (let [start (now)
                 chs   (doall (repeatedly 6 #(async/into [] (clunk/query p "SELECT pg_backend_pid() AS pid FROM pg_sleep(0.5)"))))
                 rows  (loop [[ch & more] chs
                              acc         []]
                         (if ch (recur more (into acc (async/<! ch))) acc))
                 took  (- (now) start)]
             (t/is (= 6 (count rows)))
             (t/is (= 3 (count (set (map :pid rows)))))
             (t/is (<= 1000 took 1900) (str "took " took " ms")))
           (clunk/close p)))))

(t/deftest a-streamed-query-holds-its-connection-until-cancelled
  (let [p (pool/pool (assoc config :max-size 1))]
    (run (async/go
           (let [first-pid (async/<! (pid p))
                 cancel    (async/promise-chan)
                 ;; A portal, suspended between batches of 10 rows.
                 rows      (clunk/query p ["SELECT generate_series(1, $1)::int4 AS n" 1000] {:cancel cancel :fetch-size 10})
                 _         (t/is (= {:n 1} (async/<! rows)))
                 next-pid  (pid p)]
             (let [waited? (async/<! (pending? next-pid))]
               (t/is waited? "ran while the stream held the only connection"))
             (async/close! cancel)
             ;; The same connection, back in the pool, and it still works.
             (t/is (= first-pid (async/<! next-pid)))
             (t/is (= [{:n 3}] (clunk/<query! p ["SELECT $1::int4 + 1 AS n" 2]))))
           (clunk/close p)))))

(t/deftest a-query-cancelled-while-waiting-never-runs
  (let [p (pool/pool (assoc config :max-size 1))]
    (run (async/go
           (let [hold    (async/promise-chan)
                 busy    (clunk/query p "SELECT generate_series(1, 1000)::int4 AS n" {:cancel hold})
                 _       (async/<! busy) ;; busy has the only connection now
                 ;; Enough waiters that handing the client down the line one
                 ;; call inside another would overflow the stack.
                 cancels (doall (repeatedly 20000 async/promise-chan))
                 waiters (mapv #(clunk/query p "SET application_name = 'cancelled'" {:cancel %}) cancels)]
             (run! async/close! cancels)
             (async/close! hold)
             (t/is (= [] (async/<! (async/into [] (first waiters)))))
             (t/is (= [{:application_name ""}] (clunk/<query! p "SHOW application_name"))))
           ;; Cancelled before it's made, and lent the idle connection at once,
           ;; before query's cancel watcher has had a chance to run.
           (let [rows (clunk/query p "SET application_name = 'cancelled'" {:cancel (doto (async/promise-chan) async/close!)})]
             (t/is (= [] (async/<! (async/into [] rows))))
             (t/is (= [{:application_name ""}] (clunk/<query! p "SHOW application_name"))))
           (clunk/close p)))))

(t/deftest a-lost-connection-is-replaced
  (let [p     (pool/pool (assoc config :max-size 1))
        admin (clunk/client config)]
    (run (async/go
           (async/<! (clunk/connect admin))
           ;; Killed while idle in the pool.
           (let [old (async/<! (pid p))]
             (clunk/<query! admin (str "SELECT pg_terminate_backend(" old ")"))
             (async/<! (async/timeout 100))
             (let [replaced (async/<! (pid p))]
               (t/is (int? replaced))
               (t/is (not= old replaced))))
           ;; Killed during its own query.
           (let [old      (async/<! (pid p))
                 rows     (clunk/<query! p "SELECT pg_terminate_backend(pg_backend_pid())")
                 replaced (async/<! (pid p))]
             (t/is (some clunk/error? rows))
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
             (let [[err] (clunk/<query! bad "SELECT 1")]
               (t/is (= "28P01" (:code (ex-data err))))))
           (let [[err] (clunk/<query! (pool/pool (assoc config :port 1)) "SELECT 1")]
             (t/is (clunk/error? err)))
           (t/is (= [{:n 1}] (clunk/<query! p "SELECT 1::int4 AS n")))
           (clunk/close p)
           (t/is (= "Pool is closed" (ex-message (first (clunk/<query! p "SELECT 1")))))
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
       (clunk/close p))))
