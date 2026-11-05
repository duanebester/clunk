(ns clunk.stress
  "Stress test for clunk.pool on the JVM, against the Postgres from
   docker-compose.yml. Not part of the test suite. Run it with

     clojure -M:stress [seconds 180] [workers 2000]

   Each worker is a virtual thread that runs one query after another, of a
   random kind (execute, plan read whole or left early, SQL errors, and
   query with a promise-chan or timeout :cancel; simple or with parameters;
   plain or in portal batches), and checks its rows or its error. A hang shows
   as an op still in flight hang-ms after the run stops. Meanwhile one admin client
   kills pool backends with pg_terminate_backend, idle and busy in turn, and
   another counts them in pg_stat_activity. A watch on the pool's state
   checks its invariants on every change. It exits 1 if anything failed."
  (:require [clojure.core.async :as async]
            [clojure.pprint :refer [pprint]]
            [clunk.core :as clunk]
            [clunk.pool :as pool])
  (:import (java.io File)
           (java.lang.management ManagementFactory)
           (java.nio.file Files)
           (java.util.concurrent ConcurrentHashMap ExecutionException)
           (java.util.concurrent.atomic AtomicLong)))

(def config {:username "jimmy" :password "banana" :database "world"})
(def max-size 10)
(def hang-ms
  "An op still in flight this long after the run stops counts as hung."
  60000)
(def rows-read (AtomicLong.))

;; ---- what to run and what it should return

(defn- series-sql
  "Rows a..b, as a simple query or, half the time, with a and b as
   parameters. A few sleep first, so connections stay busy and queries wait."
  [a b]
  (let [sleep (if (< (rand) 0.1) "pg_sleep(0.02), " "")]
    (if (< (rand) 0.5)
      (str "SELECT g::int4 AS n FROM " sleep "generate_series(" a ", " b ") g")
      [(str "SELECT g::int4 AS n FROM " sleep "generate_series($1::int4, $2::int4) g") a b])))

(defn- error-sql
  "Rows a..b-1, then division by zero."
  [a b]
  (if (< (rand) 0.5)
    (str "SELECT (10 / (g - " b "))::int4 * 0 + g AS n FROM generate_series(" a ", " b ") g")
    ["SELECT (10 / (g - $2))::int4 * 0 + g AS n FROM generate_series($1::int4, $2::int4) g" a b]))

(defn- fetch-opts
  "Portal batch sizes from tiny to the default."
  []
  {:fetch-size (rand-nth [nil 10 100 1000])})

(def ^:private texts ["" "plain" "naïve café" "日本語のテキスト" "emoji 🐘🚀" "quote ' and \\ back" "tab\tnew\nline"])

(defn- rand-range []
  (let [a (rand-int 1000000)
        n (if (< (rand) 0.1) (+ 5000 (rand-int 20000)) (rand-int 300))]
    [a (+ a n)]))

(defn- rows [a b] (mapv (fn [n] {:n n}) (range a (inc b))))

(defn- prefix? [got a]
  (= got (rows a (+ a (dec (count got))))))

(defn- in-order?
  "After a cancel, query's own drain takes rows too, so the rows a reader
   sees can skip some, but they stay in order within a..b."
  [got a b]
  (apply < (dec a) (concat (map :n got) [(inc b)])))

(defn- killed?
  "An error from a connection that pg_terminate_backend ended."
  [e]
  (let [e (if (instance? ExecutionException e) (ex-cause e) e)]
    (or (= "57P01" (:code (ex-data e)))
        (boolean (re-find #"Connection closed" (str (ex-message e)))))))

(defn- code-of [e]
  (:code (ex-data (if (instance? ExecutionException e) (ex-cause e) e))))

(defn- take-rows
  "Takes up to m rows from ch, fewer if it closes first. No timeouts: a hang
   shows as an op that stays in flight, which the watcher reports."
  [ch m]
  (loop [acc []]
    (if-let [v (and (< (count acc) m) (async/<!! ch))]
      (recur (conj acc v))
      acc)))

(defn- outcome
  "Runs f, mapping a thrown error from a killed connection to :killed."
  [f]
  (try (f)
       (catch Exception e
         (if (killed? e) :killed {:fail (str "threw " (code-of e) " " (ex-message e))}))))

(defn- expect-error
  "Runs f, which should throw an error with code. check, given that error,
   says whether everything else was right."
  [code f check]
  (try (f)
       {:fail "no error"}
       (catch Exception e
         (cond (killed? e)          :killed
               (not= code (code-of e)) {:fail (str "threw " (code-of e) " " (ex-message e))}
               (check)              :ok
               :else                {:fail "wrong rows before the error"}))))

(defn- run-op [p kind]
  (let [[a b] (rand-range)]
    (case kind
      :execute
      (outcome #(let [r @(clunk/execute p (series-sql a b))]
                  (if (= r (rows a b))
                    (do (.addAndGet rows-read (count r)) :ok)
                    {:fail "wrong rows"})))

      :execute-params
      ;; Text through parameters both ways, so UTF-8 sizing is in the mix.
      (outcome #(let [t (rand-nth texts)
                      r @(clunk/execute p ["SELECT $1::text AS s, $2::int4 AS n, length($1::text)::int4 AS len" t a])]
                  (if (= r [{:s t :n a :len (.count (.codePoints ^String t))}])
                    :ok
                    {:fail (str "wrong row " (pr-str r))})))

      :execute-error
      (let [[sql code] (if (< (rand) 0.5) ["SELECT * FROM no_such_table" "42P01"] [(error-sql a b) "22012"])]
        (expect-error code #(deref (clunk/execute p sql)) (constantly true)))

      :plan
      (outcome #(if (= (reduce + (range a (inc b)))
                       (transduce (map :n) + 0 (clunk/plan p (series-sql a b) (fetch-opts))))
                  (do (.addAndGet rows-read (- (inc b) a)) :ok)
                  {:fail "wrong sum"}))

      :plan-early
      (outcome #(let [m (rand-int 50)]
                  (if (= (into [] (take m) (clunk/plan p (series-sql a b) (fetch-opts)))
                         (take m (rows a b)))
                    :ok
                    {:fail "wrong prefix"})))

      :plan-error
      (let [seen (volatile! [])]
        (expect-error "22012"
                      #(run! (fn [row] (vswap! seen conj row)) (clunk/plan p (error-sql a b) (fetch-opts)))
                      #(= @seen (rows a (dec b)))))

      :cancel-promise
      (let [cancel (async/promise-chan)
            ch     (clunk/query p (series-sql a b) (assoc (fetch-opts) :cancel cancel))
            m      (rand-int 100)
            head   (take-rows ch m)]
        (async/close! cancel)
        (let [tail (take-rows ch ##Inf)]
          (cond (some clunk/error? (concat head tail))
                (if (every? #(or (not (clunk/error? %)) (killed? %)) (concat head tail)) :killed {:fail "error"})
                (and (prefix? head a) (in-order? tail (+ a (count head)) b))
                :ok
                :else                           {:fail "wrong rows"})))

      :cancel-timeout
      ;; 1 ms to 20 s, so some fire while the query waits for a connection
      ;; and some while its rows arrive.
      (let [got (take-rows (clunk/query p (series-sql a b) (assoc (fetch-opts) :cancel (async/timeout (int (Math/pow 10 (rand 4.3)))))) ##Inf)]
        (cond (some clunk/error? got)   (if (every? #(or (not (clunk/error? %)) (killed? %)) got) :killed {:fail "error"})
              (empty? got)              :cancelled-early
              (= got (rows a b))        :ok
              (in-order? got a b)       :cancelled-mid
              :else                     {:fail "wrong rows"})))))

(def kinds [:execute :execute :execute-params :execute-error :plan :plan :plan-early :plan-error :cancel-promise :cancel-timeout :cancel-timeout])

;; ---- measuring

(defn- heap-mb []
  (System/gc)
  (Thread/sleep 200)
  (System/gc)
  (let [rt (Runtime/getRuntime)]
    (quot (- (.totalMemory rt) (.freeMemory rt)) (* 1024 1024))))

(defn- sockets []
  (count (filter #(try (.startsWith (str (Files/readSymbolicLink (.toPath ^File %))) "socket:")
                       (catch Exception _ false))
                 (.listFiles (File. "/proc/self/fd")))))

(defn- threads [] (.getThreadCount (ManagementFactory/getThreadMXBean)))

(defn- backends
  "Pool backends: every client backend of jimmy on world but the admins'."
  [admin admin-pids]
  (let [[row] @(clunk/execute admin (str "SELECT count(*)::int4 AS n FROM pg_stat_activity"
                                         " WHERE usename = 'jimmy' AND datname = 'world'"
                                         " AND backend_type = 'client backend'"
                                         " AND pid NOT IN (" (apply str (interpose ", " admin-pids)) ")"))]
    (:n row)))

(defn- invariant-errors [{:keys [idle size waiters closed?]}]
  (cond-> []
    (not (<= 0 size max-size))                    (conj (str "size " size))
    (> (count idle) size)                         (conj "more idle than size")
    (and (seq idle) (seq waiters))                (conj "idle and waiters at once")
    (and (seq waiters) (not= size max-size))      (conj "waiters while not full")
    (not= (count idle) (count (set (map #(System/identityHashCode %) idle)))) (conj "a client idle twice")
    (and closed? (seq waiters))                   (conj "waiters after close")))

(defn- admin-client []
  (let [c (clunk/client config)]
    (async/<!! (clunk/connect c))
    c))

(defn- pid-of [c] (:pid (first @(clunk/execute c "SELECT pg_backend_pid() AS pid"))))

(defn -main [& args]
  (let [seconds   (Long/parseLong (or (first args) "180"))
        n-workers (Long/parseLong (or (second args) "2000"))
        killer    (admin-client)
        watcher   (admin-client)
        admins    [(pid-of killer) (pid-of watcher)]
        base      {:heap (heap-mb) :sockets (sockets) :threads (threads)}
        p         (pool/pool (assoc config :max-size max-size))
        state     (:state p)
        violations (atom [])
        _         (add-watch state ::invariants
                             (fn [_ _ _ s]
                               (when-let [errs (seq (invariant-errors s))]
                                 (swap! violations #(if (< (count %) 20) (conj % [errs (dissoc s :idle :waiters)]) %)))))
        counts    (ConcurrentHashMap.)
        failures  (atom [])
        inflight  (ConcurrentHashMap.)
        total     (AtomicLong.)
        stop?     (atom false)
        peak      (atom 0)
        kills     (atom {:idle 0 :active 0})
        samples   (atom [])
        start     (System/currentTimeMillis)
        elapsed   #(/ (- (System/currentTimeMillis) start) 1000.0)
        tally     (fn [kind result]
                    (.merge counts [kind (if (map? result) :fail result)] 1 +)
                    (when (map? result)
                      (swap! failures #(if (< (count %) 50) (conj % [kind result]) %))))
        workers   (doall
                   (for [i (range n-workers)]
                     (Thread/startVirtualThread
                      (fn []
                        (loop []
                          (when-not @stop?
                            (let [kind (rand-nth kinds)]
                              (.put inflight i [kind (System/currentTimeMillis)])
                              (let [r (try (run-op p kind)
                                           (catch Throwable e {:fail (str "harness: " e)}))]
                                (.remove inflight i)
                                (.incrementAndGet total)
                                (tally kind r)))
                            (recur)))))))
        kill-loop (Thread/startVirtualThread
                   (fn []
                     (loop [want "idle"]
                       (when-not @stop?
                         (Thread/sleep (+ 300 (rand-int 700)))
                         (let [pids (map :pid @(clunk/execute killer (str "SELECT pid FROM pg_stat_activity"
                                                                         " WHERE usename = 'jimmy' AND datname = 'world'"
                                                                         " AND backend_type = 'client backend'"
                                                                         " AND state = '" want "'"
                                                                         " AND pid NOT IN (" (apply str (interpose ", " admins)) ")")))]
                           (when-let [pid (first (shuffle pids))]
                             @(clunk/execute killer (str "SELECT pg_terminate_backend(" pid ")"))
                             (swap! kills update (keyword want) inc)))
                         (recur (if (= want "idle") "active" "idle"))))))
        watch-loop (Thread/startVirtualThread
                    (fn []
                      (loop [i 0]
                        (when-not @stop?
                          (swap! peak max (backends watcher admins))
                          (when (zero? (mod i 50))
                            (let [s @state
                                  line {:t (Math/round ^double (elapsed)) :queries (.get total)
                                        :qps (Math/round ^double (/ (.get total) (max 1.0 (elapsed))))
                                        :size (:size s) :idle (count (:idle s)) :waiters (count (:waiters s))
                                        :peak-backends @peak :sockets (sockets) :threads (threads)
                                        :oldest-ms (reduce max 0 (map #(- (System/currentTimeMillis) (second %)) (vals inflight)))}
                                  line (if (zero? (mod i 300)) (assoc line :heap-mb (heap-mb)) line)]
                              (swap! samples conj line)
                              (println line)))
                          (Thread/sleep 100)
                          (recur (inc i))))))]
    (Thread/sleep (* 1000 seconds))
    (reset! stop? true)
    (let [run-secs (elapsed)
          grace    (+ (System/currentTimeMillis) hang-ms)
          _        (doseq [^Thread t workers]
                     (.join t (java.time.Duration/ofMillis (max 1 (- grace (System/currentTimeMillis))))))
          hung     (into {} inflight)
          _        (.join kill-loop) _ (.join watch-loop)
          _        (Thread/sleep 1000)
          s        @state
          before-close {:size (:size s) :idle (count (:idle s)) :waiters (count (:waiters s))
                        :backends (backends watcher admins)}
          _        (clunk/close p)
          s2       @state
          gone     (loop [i 0] (let [n (backends watcher admins)] (if (or (zero? n) (> i 50)) n (do (Thread/sleep 100) (recur (inc i))))))
          _        (Thread/sleep 500)
          after    {:heap (heap-mb) :sockets (sockets) :threads (threads)
                    :thread-pools (frequencies (map #(clojure.string/replace (.getName ^Thread %) #"\d+" "N")
                                                    (keys (Thread/getAllStackTraces))))}
          by-kind  (reduce (fn [m [[k r] n]] (assoc-in m [k r] n)) (sorted-map) counts)
          n-fail   (reduce + (keep (fn [[[_ r] n]] (when (= :fail r) n)) counts))
          report   {:seconds        (Math/round ^double run-secs)
                    :workers        n-workers
                    :queries        (.get total)
                    :per-second     (Math/round ^double (/ (.get total) run-secs))
                    :full-read-rows-per-second (Math/round ^double (/ (.get rows-read) run-secs))
                    :by-kind        by-kind
                    :failures       n-fail
                    :failure-sample (take 10 @failures)
                    :hung           hung
                    :violations     @violations
                    :kills          @kills
                    :peak-backends  @peak
                    :before-close   before-close
                    :after-close    {:size (:size s2) :idle (count (:idle s2)) :waiters (count (:waiters s2)) :backends gone}
                    :baseline       base
                    :max-op-ms      (reduce max 0 (map :oldest-ms @samples))
                    :heap-mb-samples (keep :heap-mb @samples)
                    :end            after}]
      (println)
      (pprint report)
      (clunk/close killer)
      (clunk/close watcher)
      (shutdown-agents)
      (System/exit (if (and (zero? n-fail) (empty? hung) (empty? @violations)
                            (<= @peak max-size) (zero? gone) (zero? (:size s2))
                            (zero? (:waiters before-close)) (= (:size before-close) (:idle before-close)))
                     0 1)))))
