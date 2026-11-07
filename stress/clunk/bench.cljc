(ns clunk.bench
  "Read throughput of one client, the same on the JVM and Node, against the
   Postgres from docker-compose.yml. Not part of the test suite. Run it with

     clojure -M:bench [seconds 5] [ssl] [negotiation]
     clojure -M:cljs compile bench && node target/bench/bench.js [seconds 5] [ssl] [negotiation]

   ssl is a TLS mode, such as verify-full, and negotiation is postgres or
   direct; both need test/tls/configure-postgres.sh's certificates.

   Each case runs `callers` go loops that share one client and run the same
   query one after another, for a warmup and then `seconds` of counting, and
   reports queries and rows per second."
  (:require #?(:clj [clojure.core.async :as async] :cljs [cljs.core.async :as async])
            [clunk.async :as ca]
            [clunk.core :as clunk]))

(def config {:username "jimmy" :password "banana" :database "world"})

(defn- rows-sql [n]
  (str "SELECT g::int4 AS id, 'name ' || g AS name, g * 1.5::float8 AS score "
       "FROM generate_series(1, " n ") g"))

(def cases
  [{:name "1 row"     :sql "SELECT 1 AS n" :rows 1      :callers 1}
   {:name "1 row"     :sql "SELECT 1 AS n" :rows 1      :callers 100}
   {:name "1k rows"   :sql (rows-sql 1000) :rows 1000   :callers 1}
   {:name "1k rows"   :sql (rows-sql 1000) :rows 1000   :callers 100}
   {:name "100k rows" :sql (rows-sql 100000) :rows 100000 :callers 1}])

(defn- now [] #?(:clj (System/nanoTime) :cljs (* 1e6 (js/performance.now))))

(defn- run-for
  "Runs the case's callers for ms milliseconds; yields the number of queries
   that finished, after checking each returned all its rows."
  [client {:keys [sql rows callers]} ms]
  (let [stop (+ (now) (* ms 1e6))]
    (async/go
      (let [loops (for [_ (range callers)]
                    (async/go-loop [n 0]
                      (if (< (now) stop)
                        (let [got (async/<! (async/reduce (fn [c row]
                                                            (when (clunk/error? row) (throw row))
                                                            (inc c))
                                                          0 (ca/query client sql)))]
                          (when (not= rows got)
                            (throw (ex-info "Wrong row count" {:got got :want rows})))
                          (recur (inc n)))
                        n)))]
        (loop [[l & ls] (doall loops) total 0]
          (if l (recur ls (+ total (async/<! l))) total))))))

(defn- fmt [x] (#?(:clj format :cljs (fn [_ v] (.toFixed v 0))) "%.0f" (double x)))

(defn run [seconds ssl negotiation]
  (async/go
    (let [client (clunk/client (cond-> config
                                 ssl         (assoc :ssl (keyword ssl) :ssl-root-cert "test/tls/certs/ca.crt")
                                 negotiation (assoc :ssl-negotiation (keyword negotiation))))]
      (async/<! (ca/connect client))
      (println (str "platform " #?(:clj "jvm" :cljs "node") ", " seconds " s per case, ssl " (or ssl "disable")
                    (when negotiation (str " " negotiation))))
      (doseq [{:keys [name rows callers] :as c} cases]
        (async/<! (run-for client c 1000)) ;; warmup
        (let [t0 (now)
              n  (async/<! (run-for client c (* 1000 seconds)))
              s  (/ (- (now) t0) 1e9)]
          (println (str name ", " callers " callers: " (fmt (/ n s)) " queries/s, "
                        (fmt (/ (* n rows) s)) " rows/s"))))
      (clunk/close client))))

#?(:clj
   (defn -main [& [seconds ssl negotiation]]
     (async/<!! (run (Long/parseLong (or seconds "5")) ssl negotiation))
     (shutdown-agents)
     (System/exit 0)))

#?(:cljs
   (defn main [& [seconds ssl negotiation]]
     (async/take! (run (js/parseInt (or seconds "5") 10) ssl negotiation) (fn [_] (js/process.exit 0)))))
