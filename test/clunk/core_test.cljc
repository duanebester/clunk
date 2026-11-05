(ns clunk.core-test
  "Runs against the Postgres from docker-compose.yml (jimmy/banana, db world)
   on the JVM (clojure -X:test) and on Node (clojure -M:cljs compile test)."
  (:require #?(:clj [clojure.test :as t] :cljs [cljs.test :as t])
            [clojure.core.async :as async]
            [clunk.core :as clunk])
  #?(:clj (:import (java.time LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZoneOffset))))

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

(t/deftest a-wrong-password-fails-connect
  (let [c (clunk/client (assoc config :password "wrong"))]
    (run (async/go
           (t/is (clunk/error? (async/<! (clunk/connect c))))
           (t/is (clunk/error? (first (clunk/<query! c "SELECT 1"))))
           (clunk/close c)))))
