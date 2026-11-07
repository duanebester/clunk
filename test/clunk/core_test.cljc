(ns clunk.core-test
  "Runs against the Postgres from docker-compose.yml (jimmy/banana, db world)
   on the JVM (clojure -X:test) and on Node (clojure -M:cljs compile test)."
  (:require #?(:clj [clojure.test :as t] :cljs [cljs.test :as t])
            [clojure.core.async :as async]
            [clunk.core :as clunk]
            [clunk.messages :as messages]
            [clunk.oid-types :as oid-types]
            [clunk.vendor.binf :as binf])
  #?(:clj (:import (clojure.lang ExceptionInfo)
                   (java.time LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZoneOffset)
                   (java.util.concurrent ExecutionException))))

(def config {:username "jimmy" :password "banana" :database "world"})

(defn- run
  "Runs a go block's channel to completion, failing the test after 10 s."
  [ch]
  (let [result (async/go (async/alt! ch ([v] v)
                                     (async/timeout 10000) (t/is false "timed out")))]
    #?(:clj (async/<!! result)
       :cljs (t/async done (async/take! result (fn [_] (done)))))))

(t/deftest queries-run-in-order-and-repeat
  (let [c (clunk/client config)]
    (run (async/go
           ;; Queued before the connection is ready; they must wait their turn.
           (let [a     (clunk/query c "SELECT 1::int4 AS n, 'a'::varchar AS s")
                 b     (clunk/query c "SELECT generate_series(1, 3)::int4 AS n")
                 ready (async/<! (clunk/connect c))]
             (t/is (= :ReadyForQuery (:type ready)))
             (t/is (= [{:n 1 :s "a"}] (async/<! (async/into [] a))))
             (t/is (= [{:n 1} {:n 2} {:n 3}] (async/<! (async/into [] b))))
             (t/is (= [{:n 1 :s "a"}] (clunk/<query! c "SELECT 1::int4 AS n, 'a'::varchar AS s")))
             (t/is (= [{:n nil :s "b"}] (clunk/<query! c "SELECT NULL::int4 AS n, 'b'::varchar AS s")))
             (clunk/close c))))))

(t/deftest a-result-larger-than-one-read-arrives-whole
  (let [c (clunk/client config)]
    (run (async/go
           (async/<! (clunk/connect c))
           ;; About 2.4 MB: many socket reads, with messages cut across them.
           (let [rows (clunk/<query! c "SELECT g::int4 AS n, repeat('x', 100)::varchar AS s FROM generate_series(1, 20000) g")]
             (t/is (= 20000 (count rows)))
             (t/is (= (range 1 20001) (map :n rows)))
             (t/is (every? #(= (apply str (repeat 100 "x")) (:s %)) rows)))
           (t/is (= [{:n 1}] (clunk/<query! c "SELECT 1::int4 AS n")))
           (clunk/close c)))))

(t/deftest a-failed-query-reports-an-error-and-the-next-one-runs
  (let [c (clunk/client config)]
    (run (async/go
           (async/<! (clunk/connect c))
           (let [[err & more] (clunk/<query! c "SELECT * FROM no_such_table")]
             (t/is (clunk/error? err))
             (t/is (= "42P01" (:code (ex-data err))))
             (t/is (empty? more)))
           (t/is (= [{:n 7}] (clunk/<query! c "SELECT 7::int4 AS n")))
           (t/is (= [{:b true :f 1.5 :d #?(:clj 12.50M :cljs "12.50") :i -32768 :c "ab  "}]
                    (clunk/<query! c "SELECT true AS b, 1.5::float4 AS f, 12.50::numeric AS d, (-32768)::int2 AS i, 'ab'::char(4) AS c")))
           ;; 2^53 + 1 is the first int8 a JS number can't hold.
           (t/is (= [{:l #?(:clj 9007199254740993 :cljs "9007199254740993") :g 0.1 :t "text"}]
                    (clunk/<query! c "SELECT 9007199254740993::int8 AS l, 0.1::float8 AS g, 'text'::text AS t")))
           (clunk/close c)))))

(def ^:private decoder oid-types/decoder)

(defn- set-decoder!
  "Replaces oid-types/decoder, which each query looks its decoders up in."
  [f]
  #?(:clj (alter-var-root #'oid-types/decoder (constantly f))
     :cljs (set! oid-types/decoder f)))

(t/deftest a-value-that-fails-to-decode-fails-only-its-query
  (let [c   (clunk/client config)
        sql "SELECT g::int4 AS n, CASE WHEN g = 50 THEN point(1, 2) END AS p FROM generate_series(1, 100) g"]
    (run (async/go
           (async/<! (clunk/connect c))
           ;; point is read as raw bytes; make it throw, as a parser does on a
           ;; value it doesn't expect (NaN for a JVM numeric, say). NULL isn't decoded.
           (set-decoder! (fn [oid]
                           (if (= 600 oid)
                             (fn [_ _] (throw #?(:clj (IllegalArgumentException. "bad point") :cljs (js/Error. "bad point"))))
                             (decoder oid))))
           ;; The rows before it arrive, then its error, and nothing after it.
           (let [rows (clunk/<query! c sql)]
             (t/is (= (for [n (range 1 50)] {:n n :p nil}) (butlast rows)))
             (t/is (= "Could not decode column p (type 600): bad point" (ex-message (last rows))))
             (t/is (= {:column "p" :oid 600 :sql sql} (ex-data (last rows)))))
           (t/is (= [{:n 7}] (clunk/<query! c "SELECT 7::int4 AS n")))
           ;; In a portal the server stops after the batch that held it, of 100
           ;; rows: nextval counts the rows it produced.
           (clunk/<query! c "CREATE TEMP SEQUENCE decode_rows")
           (let [rows (async/<! (async/into [] (clunk/query c "SELECT nextval('decode_rows')::int4 AS n, CASE WHEN g = 50 THEN point(1, 2) END AS p FROM generate_series(1, 100000) g"
                                                            {:cancel (async/promise-chan) :fetch-size 100})))]
             (t/is (= 49 (count (butlast rows))))
             (t/is (= 600 (:oid (ex-data (last rows))))))
           (t/is (= [{:n 100}] (clunk/<query! c "SELECT last_value::int4 AS n FROM decode_rows")))
           (set-decoder! decoder)
           (clunk/close c)))))

#?(:clj
   (t/deftest execute-returns-a-future-and-plan-a-reducible
     (let [c (clunk/client config)]
       (async/<!! (clunk/connect c))
       (t/is (= [{:n 1} {:n 2}] @(clunk/execute c "SELECT generate_series(1, 2)::int4 AS n")))
       (let [err (try @(clunk/execute c "SELECT * FROM no_such_table")
                      (catch ExecutionException e (ex-cause e)))]
         (t/is (= "42P01" (:code (ex-data err)))))
       (t/is (= 5050 (transduce (map :n) + 0 (clunk/plan c "SELECT generate_series(1, 100)::int4 AS n"))))
       (t/is (thrown-with-msg? ExceptionInfo #"no_such_table" (into [] (clunk/plan c "SELECT * FROM no_such_table"))))
       ;; The pause lets the client fill the row buffer before the reduce stops,
       ;; so stopping early must still let the next query run.
       (t/is (= {:n 1} (reduce (fn [_ row] (Thread/sleep 200) (reduced row))
                               nil
                               (clunk/plan c "SELECT generate_series(1, 1000)::int4 AS n"))))
       (t/is (= [{:n 7}] (deref (clunk/execute c "SELECT 7::int4 AS n") 5000 :timed-out)))
       ;; nextval runs once per row the server produces, so the sequence counts
       ;; them: stopping early must stop the server after the current batch.
       @(clunk/execute c "CREATE TEMP SEQUENCE plan_rows")
       (t/is (= [1 2] (into [] (comp (map :n) (take 2))
                            (clunk/plan c ["SELECT nextval('plan_rows')::int4 AS n FROM generate_series(1, $1)" 100000]
                                        {:fetch-size 100}))))
       (t/is (= [{:n 100}] @(clunk/execute c "SELECT last_value::int4 AS n FROM plan_rows")))
       ;; NaN and Infinity are numerics with no BigDecimal, so they fail to
       ;; decode, and fail only their own query.
       (let [err (try (deref (clunk/execute c "SELECT 1::int4 AS n, 'NaN'::numeric AS x") 5000 :timed-out)
                      (catch ExecutionException e (ex-cause e)))]
         (t/is (= {:column "x" :oid 1700 :sql "SELECT 1::int4 AS n, 'NaN'::numeric AS x"} (ex-data err))))
       (let [err (deref (future (try (into [] (clunk/plan c "SELECT 'Infinity'::numeric AS x"))
                                     (catch ExceptionInfo e e)))
                        5000 :timed-out)]
         (t/is (= 1700 (:oid (ex-data err)))))
       (t/is (= [{:n 7}] (deref (clunk/execute c "SELECT 7::int4 AS n") 5000 :timed-out)))
       (clunk/close c))))

#?(:clj
   (t/deftest a-query-that-takes-a-connection-as-it-drops-still-ends
     ;; The window between submit taking the idle connection and start!
     ;; setting the running query, played in order: the server hangs up in
     ;; it, so on-close finds no query to end.
     (let [admin (clunk/client config)
           c     (clunk/client config)
           _     (async/<!! (clunk/connect admin))
           _     (async/<!! (clunk/connect c))
           pid   (:pid (first @(clunk/execute c "SELECT pg_backend_pid() AS pid")))
           ended (async/promise-chan)
           req   {:sql "SELECT 1" :fetch-size 0 :cancelled (atom false)
                  :sink (reify clunk/Sink
                          (-row! [_ _ _] true)
                          (-flush! [_ _] true)
                          (-error! [_ _ err] (async/put! ended err) true)
                          (-end! [_ _] (async/put! ended :ended)))}]
       (swap! (:state c) assoc :busy? true)
       @(clunk/execute admin ["SELECT pg_terminate_backend($1::int4)" pid])
       (t/is (= :failed (first (async/alts!! [(async/go-loop []
                                                 (let [p (:phase @(:state c))]
                                                   (if (= :failed p) p (do (async/<! (async/timeout 10)) (recur)))))
                                               (async/timeout 5000)]))))
       (#'clunk/start! c req)
       (t/is (= "Connection closed during query"
                (ex-message (first (async/alts!! [ended (async/timeout 3000)])))))
       (clunk/close admin))))

#?(:clj
   (t/deftest each-connection-reads-on-a-daemon-thread-of-its-own
     (let [readers #(set (filter (fn [^Thread t] (= "clunk-reader-localhost:5432" (.getName t)))
                                 (keys (Thread/getAllStackTraces))))
           before  (readers)
           c       (clunk/client config)
           _       (async/<!! (clunk/connect c))
           [^Thread reader :as new] (vec (remove before (readers)))]
       (t/is (= 1 (count new)))
       (t/is (.isDaemon reader))
       (clunk/close c)
       (.join reader 2000)
       (t/is (not (.isAlive reader)) "the reader ends once the connection is closed"))))

(t/deftest a-cancelled-query-closes-early-and-the-next-one-runs
  (let [c (clunk/client config)]
    (run (async/go
           (async/<! (clunk/connect c))
           ;; nextval runs once per row the server produces, so the sequence counts them.
           (clunk/<query! c "CREATE TEMP SEQUENCE cancel_rows")
           (let [cancel (async/promise-chan)
                 ch     (clunk/query c "SELECT nextval('cancel_rows')::int4 AS n FROM generate_series(1, 100000)" {:cancel cancel})]
             (t/is (= {:n 1} (async/<! ch)))
             ;; By now the client has filled ch and waits to put the next row.
             (async/<! (async/timeout 200))
             (async/close! cancel)
             (t/is (= [{:n 7}] (clunk/<query! c "SELECT 7::int4 AS n")))
             (t/is (> 999 (count (async/<! (async/into [] ch))))))
           ;; The server stopped after the first batch, of the default 1000 rows.
           (t/is (= [{:n 1000}] (clunk/<query! c "SELECT last_value::int4 AS n FROM cancel_rows")))
           (clunk/close c)))))

(defn- bytes-of [b]
  (vec #?(:clj b :cljs (js/Uint8Array. b))))

(t/deftest dates-times-bytes-and-text-decode
  (let [c (clunk/client config)]
    (run (async/go
           (async/<! (clunk/connect c))
           ;; Chicago's offset before 1883 was -05:50:36, which tests offset seconds.
           (clunk/<query! c "SET TimeZone = 'America/Chicago'")
           ;; Node reads date and timestamp in local time, as node-postgres does.
           (t/is (= [#?(:clj {:a (LocalDate/of 2026 11 5) :b (LocalDate/of 99 6 1) :c (LocalDate/of -43 3 15)
                              :d (LocalDate/of 12345 1 2) :e LocalDate/MAX :f OffsetDateTime/MIN}
                        :cljs {:a (js/Date. 2026 10 5) :b (js/Date. "0099-06-01T00:00:00") :c (js/Date. "-000043-03-15T00:00:00")
                               :d (js/Date. "+012345-01-02T00:00:00") :e js/Infinity :f (- js/Infinity)})]
                    (clunk/<query! c "SELECT '2026-11-05'::date AS a, '0099-06-01'::date AS b, '0044-03-15 BC'::date AS c, '12345-01-02'::date AS d, 'infinity'::date AS e, '-infinity'::timestamptz AS f")))
           (t/is (= [#?(:clj {:a (LocalDateTime/of 2026 11 5 13 45 1 123456000)
                              :b (OffsetDateTime/of 2026 11 5 7 45 1 500000000 (ZoneOffset/ofHours -6))
                              :c (OffsetDateTime/of 1849 12 31 18 9 24 0 (ZoneOffset/ofHoursMinutesSeconds -5 -50 -36))
                              :d (OffsetDateTime/of -43 3 15 6 9 24 0 (ZoneOffset/ofHoursMinutesSeconds -5 -50 -36))}
                        :cljs {:a (js/Date. 2026 10 5 13 45 1 123)
                               :b (js/Date. "2026-11-05T13:45:01.5Z")
                               :c (js/Date. "1850-01-01T00:00:00Z")
                               :d (js/Date. "-000043-03-15T12:00:00Z")})]
                    (clunk/<query! c "SELECT '2026-11-05 13:45:01.123456'::timestamp AS a, '2026-11-05 13:45:01.5+00'::timestamptz AS b, '1850-01-01 00:00+00'::timestamptz AS c, '0044-03-15 12:00+00 BC'::timestamptz AS d")))
           (let [[row] (clunk/<query! c "SELECT '13:45:01.25'::time AS a, '24:00:00'::time AS b, '13:45:01-05:30'::timetz AS c, 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid AS d, '{\"a\":1}'::json AS e, '{\"a\": 1}'::jsonb AS f, 'pg_class'::name AS g, 4294967295::oid AS h, '\\xdeadbeef00'::bytea AS x, ''::bytea AS y")]
             (t/is (= #?(:clj {:a (LocalTime/of 13 45 1 250000000) :b LocalTime/MAX
                               :c (OffsetTime/of 13 45 1 0 (ZoneOffset/ofHoursMinutes -5 -30))}
                         :cljs {:a "13:45:01.25" :b "24:00:00" :c "13:45:01-05:30"})
                      (select-keys row [:a :b :c])))
             (t/is (= {:d "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11" :e "{\"a\":1}" :f "{\"a\": 1}" :g "pg_class" :h 4294967295}
                      (select-keys row [:d :e :f :g :h])))
             (t/is (= #?(:clj [-34 -83 -66 -17 0] :cljs [222 173 190 239 0]) (bytes-of (:x row))))
             (t/is (= [] (bytes-of (:y row)))))
           ;; 400 KB arrives over many reads, so it decodes from a reassembled buffer.
           (let [[{b :b}] (clunk/<query! c "SELECT decode(repeat('00ff7f80', 100000), 'hex') AS b")]
             (t/is (= (take 400000 (cycle #?(:clj [0 -1 127 -128] :cljs [0 255 127 128]))) (bytes-of b))))
           (clunk/close c)))))

(t/deftest parameters-bind-and-a-failed-batch-lets-the-next-query-run
  (let [c (clunk/client config)]
    (run (async/go
           (async/<! (clunk/connect c))
           ;; The server infers $1's type from the column it is compared with.
           (t/is (= [{:name "United States"}]
                    (clunk/<query! c ["SELECT name FROM (VALUES ('USA'::text, 'United States'::text), ('FRA', 'France')) AS country(code, name) WHERE code = $1" "USA"])))
           ;; Zürich is 6 characters in 7 bytes.
           (t/is (= [{:a 42 :b "Zürich" :n 6 :c true :d nil :e #?(:clj 12.50M :cljs "12.50")}]
                    (clunk/<query! c ["SELECT $1::int4 AS a, $2::text AS b, length($2::text) AS n, $3::bool AS c, $4::int4 AS d, $5::numeric AS e"
                                      42 "Zürich" true nil #?(:clj 12.50M :cljs "12.50")])))
           (let [[row] (clunk/<query! c ["SELECT $1::bytea AS x, $2::date AS d, $3::timestamp AS ts, $4::timestamptz AS tz"
                                         #?(:clj (byte-array [-34 -83 0]) :cljs (.-buffer (js/Uint8Array. #js [222 173 0])))
                                         #?(:clj (LocalDate/of 2026 11 5) :cljs (js/Date. 2026 10 5))
                                         #?(:clj (LocalDateTime/of 2026 11 5 13 45 1 123456000) :cljs (js/Date. 2026 10 5 13 45 1 123))
                                         #?(:clj (OffsetDateTime/of 2026 11 5 7 45 1 500000000 (ZoneOffset/ofHours -6)) :cljs (js/Date. "2026-11-05T13:45:01.5Z"))])]
             (t/is (= #?(:clj [-34 -83 0] :cljs [222 173 0]) (bytes-of (:x row))))
             (t/is (= #?(:clj [(LocalDate/of 2026 11 5) (LocalDateTime/of 2026 11 5 13 45 1 123456000) (.toInstant (OffsetDateTime/of 2026 11 5 13 45 1 500000000 ZoneOffset/UTC))]
                         :cljs [(js/Date. 2026 10 5) (js/Date. 2026 10 5 13 45 1 123) (js/Date. "2026-11-05T13:45:01.5Z")])
                      [(:d row) (:ts row) #?(:clj (.toInstant ^OffsetDateTime (:tz row)) :cljs (:tz row))])))
           (let [[err & more] (clunk/<query! c ["SELECT $1::int4, $2::int4" 1])]
             (t/is (= "08P01" (:code (ex-data err))))
             (t/is (empty? more)))
           ;; Fails in the fifth batch, after the server has suspended the portal four times.
           (let [rows (async/<! (async/into [] (clunk/query c "SELECT (1 / (450 - g))::int4 AS x FROM generate_series(1, 1000) g"
                                                            {:cancel (async/promise-chan) :fetch-size 100})))]
             (t/is (= 449 (count (butlast rows))))
             (t/is (= "22012" (:code (ex-data (last rows))))))
           (t/is (= [{:n 7}] (clunk/<query! c ["SELECT $1::int4 AS n" 7])))
           (clunk/close c)))))

(t/deftest non-ascii-sql-user-and-password-reach-the-server
  (let [c (clunk/client config)]
    (run (async/go
           (async/<! (clunk/connect c))
           (t/is (= [{:city "Zürich"}] (clunk/<query! c "SELECT 'Zürich' AS city")))
           (t/is (= [{:s "🦀🐘" :n 8}] (clunk/<query! c "SELECT '🦀🐘' AS s, octet_length('🦀🐘') AS n")))
           ;; The user name goes in the startup message; SCRAM hashes the password.
           (clunk/<query! c "DROP ROLE IF EXISTS \"clünk_test\"")
           (clunk/<query! c "CREATE ROLE \"clünk_test\" LOGIN PASSWORD 'pässwörd'")
           (let [u (clunk/client {:username "clünk_test" :password "pässwörd" :database "world"})]
             (async/<! (clunk/connect u))
             (t/is (= [{:u "clünk_test"}] (clunk/<query! u "SELECT current_user AS u")))
             (clunk/close u))
           (clunk/<query! c "DROP ROLE \"clünk_test\"")
           (clunk/close c)))))

(t/deftest text-converts-to-and-from-a-latin1-database
  (let [c (clunk/client config)]
    (run (async/go
           (async/<! (clunk/connect c))
           (clunk/<query! c "DROP DATABASE IF EXISTS clunk_latin1 WITH (FORCE)")
           (clunk/<query! c "CREATE DATABASE clunk_latin1 ENCODING 'LATIN1' LC_COLLATE 'C' LC_CTYPE 'C' TEMPLATE template0")
           (let [l (clunk/client (assoc config :database "clunk_latin1"))]
             (async/<! (clunk/connect l))
             ;; Read as Latin-1, the UTF-8 bytes would be 7 characters.
             (t/is (= [{:s "Zürich" :n 6}] (clunk/<query! l "SELECT 'Zürich' AS s, char_length('Zürich') AS n")))
             (t/is (= "22P05" (:code (ex-data (first (clunk/<query! l "SELECT '🦀' AS s")))))
                   "LATIN1 has no emoji, so the server rejects it")
             (clunk/close l))
           (clunk/<query! c "DROP DATABASE clunk_latin1 WITH (FORCE)")
           (clunk/close c)))))

(defn- unsigned [view]
  (mapv #(bit-and % 0xff) (bytes-of (binf/backing-buffer view))))

(t/deftest frontend-messages-are-sized-in-utf-8-bytes
  ;; "pä🦀" is 3 characters but 1 + 2 + 4 = 7 bytes.
  (let [s [0x70 0xc3 0xa4 0xf0 0x9f 0xa6 0x80]]
    (t/is (= (into [0x70 0 0 0 12] (conj s 0)) (unsigned (messages/build-password "pä🦀"))))
    (t/is (= (into [0x70 0 0 0 17 0x4d 0 0 0 0 7] s) (unsigned (messages/build-sasl-initial-response "M" "pä🦀"))))
    (t/is (= (into [0x70 0 0 0 11] s) (unsigned (messages/build-sasl-response "pä🦀"))))))

(t/deftest a-wrong-password-fails-connect
  (let [c (clunk/client (assoc config :password "wrong"))]
    (run (async/go
           (t/is (clunk/error? (async/<! (clunk/connect c))))
           (t/is (clunk/error? (first (clunk/<query! c "SELECT 1"))))
           (clunk/close c)))))
