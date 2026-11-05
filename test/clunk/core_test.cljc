(ns clunk.core-test
  "Runs against the Postgres from docker-compose.yml (jimmy/banana, db world)
   on the JVM (clojure -X:test) and on Node (clojure -M:cljs compile test)."
  (:require #?(:clj [clojure.test :as t] :cljs [cljs.test :as t])
            [clojure.core.async :as async]
            [clunk.core :as clunk]))

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

(t/deftest a-wrong-password-fails-connect
  (let [c (clunk/client (assoc config :password "wrong"))]
    (run (async/go
           (t/is (clunk/error? (async/<! (clunk/connect c))))
           (t/is (clunk/error? (first (clunk/<query! c "SELECT 1"))))
           (clunk/close c)))))
