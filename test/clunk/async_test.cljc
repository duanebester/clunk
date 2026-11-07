(ns clunk.async-test
  "clunk.async's channel API, against the Postgres from docker-compose.yml
   (jimmy/banana, db world), on the JVM and on Node. Uses no tables."
  (:require #?(:clj [clojure.test :as t] :cljs [cljs.test :as t])
            [clojure.core.async :as async]
            [clunk.async :as ca :refer [<query!]]
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

(defn- within
  "Inside a go block, the next value of ch, nil once it closes, or ::timeout
   after ms."
  [ch ms]
  (async/go (async/alt! ch ([v] v) (async/timeout ms) ::timeout)))

(defn- connected []
  (async/go (let [c (clunk/client config)]
              (async/<! (ca/connect c))
              c)))

(defn- pid [c]
  (async/go (:pid (first (<query! c "SELECT pg_backend_pid() AS pid")))))

(t/deftest queries-run-in-order-and-errors-arrive-on-the-channel
  (let [c (clunk/client config)]
    (run (async/go
           ;; Queued before the connection is ready; they must wait their turn.
           (let [a     (ca/query c "SELECT 1::int4 AS n, 'a'::varchar AS s")
                 b     (ca/query c ["SELECT generate_series(1, $1)::int4 AS n" 3])
                 ready (async/<! (ca/connect c))]
             (t/is (= :ReadyForQuery (:type ready)))
             (t/is (= [{:n 1 :s "a"}] (async/<! (async/into [] a))))
             (t/is (= [{:n 1} {:n 2} {:n 3}] (async/<! (async/into [] b))))
             (t/is (= [{:n nil :s "b"}] (<query! c "SELECT NULL::int4 AS n, 'b'::varchar AS s")))
             ;; The rows before an error arrive, then the error, and the next query runs.
             (let [rows (<query! c "SELECT (1 / (3 - g))::int4 AS x FROM generate_series(1, 3) g")]
               (t/is (= [{:x 0} {:x 1}] (butlast rows)))
               (t/is (= "22012" (:code (ex-data (last rows))))))
             (t/is (= [{:n 7}] (<query! c "SELECT 7::int4 AS n")))
             (clunk/close c))))))

(t/deftest connect-yields-an-error-for-a-wrong-password
  (let [c (clunk/client (assoc config :password "wrong"))]
    (run (async/go
           (t/is (= "28P01" (:code (ex-data (async/<! (ca/connect c))))))
           (t/is (clunk/error? (first (<query! c "SELECT 1"))))
           (clunk/close c)))))

(t/deftest a-cancelled-query-closes-early-and-the-next-one-runs
  (let [c (clunk/client config)]
    (run (async/go
           (async/<! (ca/connect c))
           ;; nextval runs once per row the server produces, so the sequence counts them.
           (<query! c "CREATE TEMP SEQUENCE cancel_rows")
           (let [cancel (async/promise-chan)
                 ch     (ca/query c "SELECT nextval('cancel_rows')::int4 AS n FROM generate_series(1, 100000)" {:cancel cancel})]
             (t/is (= {:n 1} (async/<! ch)))
             ;; By now the client has filled ch and waits to put the next row.
             (async/<! (async/timeout 200))
             (async/close! cancel)
             (t/is (= [{:n 7}] (<query! c "SELECT 7::int4 AS n")))
             (t/is (> 999 (count (async/<! (async/into [] ch))))))
           ;; The server stopped after the first batch, of the default 1000 rows.
           (t/is (= [{:n 1000}] (<query! c "SELECT last_value::int4 AS n FROM cancel_rows")))
           ;; A value put on a plain channel cancels too.
           (let [cancel (async/chan)
                 ch     (ca/query c "SELECT generate_series(1, 100000)::int4 AS n" {:cancel cancel :fetch-size 100})]
             (t/is (= {:n 1} (async/<! ch)))
             (async/>! cancel :stop)
             (t/is (> 199 (count (async/<! (async/into [] ch)))))
             (t/is (= [{:n 7}] (<query! c "SELECT 7::int4 AS n"))))
           (clunk/close c)))))

(t/deftest a-streamed-query-holds-its-pool-connection-until-cancelled
  (let [p (pool/pool (assoc config :max-size 1))]
    (run (async/go
           (let [first-pid (async/<! (pid p))
                 cancel    (async/promise-chan)
                 ;; A portal, suspended between batches of 10 rows.
                 rows      (ca/query p ["SELECT generate_series(1, $1)::int4 AS n" 1000] {:cancel cancel :fetch-size 10})
                 _         (t/is (= {:n 1} (async/<! rows)))
                 next-pid  (pid p)]
             (t/is (= ::timeout (async/<! (within next-pid 300))) "ran while the stream held the only connection")
             (async/close! cancel)
             ;; The same connection, back in the pool, and it still works.
             (t/is (= first-pid (async/<! next-pid)))
             (t/is (= [{:n 3}] (<query! p ["SELECT $1::int4 + 1 AS n" 2]))))
           (clunk/close p)))))

(t/deftest a-query-cancelled-while-waiting-for-the-pool-never-runs
  (let [p (pool/pool (assoc config :max-size 1))]
    (run (async/go
           (let [hold    (async/promise-chan)
                 busy    (ca/query p "SELECT generate_series(1, 1000)::int4 AS n" {:cancel hold})
                 _       (async/<! busy) ;; busy has the only connection now
                 ;; Enough waiters that handing the client down the line one
                 ;; call inside another would overflow the stack.
                 cancels (doall (repeatedly 20000 async/promise-chan))
                 waiters (mapv #(ca/query p "SET application_name = 'cancelled'" {:cancel %}) cancels)]
             (run! async/close! cancels)
             (async/close! hold)
             (t/is (= [] (async/<! (async/into [] (first waiters)))))
             (t/is (= [{:application_name ""}] (<query! p "SHOW application_name"))))
           ;; Cancelled before it's made, and lent the idle connection at once,
           ;; before query's cancel watcher has had a chance to run.
           (let [rows (ca/query p "SET application_name = 'cancelled'" {:cancel (doto (async/promise-chan) async/close!)})]
             (t/is (= [] (async/<! (async/into [] rows))))
             (t/is (= [{:application_name ""}] (<query! p "SHOW application_name"))))
           (clunk/close p)))))

(t/deftest notifications-arrive-on-a-channel-during-a-streamed-query
  (run (async/go
         (let [a     (async/<! (connected))
               b     (async/<! (connected))
               ch    (ca/listen a "clunk_mid")
               _     (<query! a "SELECT 1")
               b-pid (async/<! (pid b))]
           ;; Notified from elsewhere while a streamed query is between batches:
           ;; the server holds it until the query's transaction ends, and the
           ;; rows around it are untouched.
           (let [cancel (async/promise-chan)
                 rows   (ca/query a ["SELECT g::int4 AS n FROM generate_series(1, $1) g" 100] {:cancel cancel :fetch-size 10})
                 head   (async/<! rows)]
             (<query! b "SELECT pg_notify('clunk_mid', 'from b')")
             (t/is (= (map (fn [n] {:n n}) (range 1 101))
                      (cons head (async/<! (async/into [] rows)))))
             (t/is (= {:channel "clunk_mid" :payload "from b" :pid b-pid} (async/<! (within ch 2000)))))
           (clunk/close a)
           (clunk/close b)))))

(t/deftest a-slow-listener-channel-does-not-hold-up-queries
  (run (async/go
         (let [a  (async/<! (connected))
               b  (async/<! (connected))
               ch (ca/listen a "clunk_slow" {:max-queued 5})]
           (<query! a "SELECT 1")
           ;; Ten in one transaction, none taken yet.
           (<query! b "SELECT pg_notify('clunk_slow', g::text) FROM generate_series(1, 10) g")
           (t/is (= [{:n 7}] (<query! a "SELECT 7::int4 AS n")))
           (t/is (= ["1" "2" "3" "4" "5"]
                    (loop [ps []] (if (< (count ps) 5) (recur (conj ps (:payload (async/<! ch)))) ps))))
           ;; The sixth found five waiting, so the listener fell behind and closed.
           (let [err (async/<! ch)]
             (t/is (clunk/error? err))
             (t/is (= "Listener fell behind" (ex-message err))))
           (t/is (nil? (async/<! ch)))
           ;; Its UNLISTEN went once it was gone.
           (t/is (= [] (<query! a "SELECT pg_listening_channels() AS c")))
           (clunk/close a)
           (clunk/close b)))))

(t/deftest unlisten-and-closing-the-channel-stop-delivery
  (run (async/go
         (let [a   (async/<! (connected))
               b   (async/<! (connected))
               one (ca/listen a "clunk_u")
               two (ca/listen a "clunk_u")]
           (<query! a "SELECT 1")
           (<query! b "SELECT pg_notify('clunk_u', 'both')")
           (t/is (= "both" (:payload (async/<! (within one 2000)))))
           (t/is (= "both" (:payload (async/<! (within two 2000)))))
           ;; Unlistening one channel closes it, and the other still listens.
           (t/is (nil? (clunk/unlisten a one)))
           (t/is (nil? (async/<! (within one 2000))))
           (<query! b "SELECT pg_notify('clunk_u', 'two only')")
           (t/is (= "two only" (:payload (async/<! (within two 2000)))))
           ;; Unlistening the name closes the rest and stops the server.
           (clunk/unlisten a "clunk_u")
           (t/is (nil? (async/<! (within two 2000))))
           (t/is (= [] (<query! a "SELECT pg_listening_channels() AS c")))
           ;; A channel closed by its reader is dropped at its next notification.
           (let [ch (ca/listen a "clunk_closed")]
             (<query! a "SELECT 1")
             (async/close! ch)
             (<query! b "SELECT pg_notify('clunk_closed', 'dropped')")
             (async/<! (async/timeout 200))
             (t/is (= [] (<query! a "SELECT pg_listening_channels() AS c"))))
           ;; A pool's listener channel closes, with no error, when the pool does.
           (let [p  (pool/pool config)
                 ch (ca/listen p "clunk_pool")]
             (<query! @(:listen-client p) "SELECT 1")
             (clunk/close p)
             (t/is (nil? (async/<! (within ch 2000)))))
           (clunk/close a)
           (clunk/close b)))))
