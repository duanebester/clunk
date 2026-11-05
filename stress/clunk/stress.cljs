(ns clunk.stress
  "Stress test for clunk.pool on Node, against the Postgres from
   docker-compose.yml. Not part of the test suite. Run it with

     clojure -M:cljs compile stress
     node --expose-gc target/stress/stress.js [seconds 180] [workers 2000]

   Each worker is a go loop that runs one query after another through the
   JavaScript API (query, stream, stream left early as break does, SQL
   errors) or clunk.core/query with a promise-chan or timeout :cancel, simple
   or with parameters, plain or in portal batches, and checks its rows or its
   error. A hang shows as an op still in flight hang-ms after the run stops.
   Meanwhile one admin client kills pool backends with pg_terminate_backend,
   idle and busy in turn, and another counts them in pg_stat_activity. A watch
   on the pool's state checks its invariants on every change. It exits 1 if
   anything failed."
  (:require [cljs.core.async :as async]
            [cljs.pprint :refer [pprint]]
            [clunk.core :as clunk]
            [clunk.node :as node]))

(def config {:username "jimmy" :password "banana" :database "world"})
(def max-size 10)
(def hang-ms
  "An op still in flight this long after the run stops counts as hung."
  60000)
(def rows-read (atom 0))

;; ---- what to run and what it should return

(defn- series
  "Rows a..b, as [sql params]: a simple query (params nil) or, half the time,
   with a and b as parameters. A few sleep first, so connections stay busy
   and queries wait."
  [a b]
  (let [sleep (if (< (rand) 0.1) "pg_sleep(0.02), " "")]
    (if (< (rand) 0.5)
      [(str "SELECT g::int4 AS n FROM " sleep "generate_series(" a ", " b ") g") nil]
      [(str "SELECT g::int4 AS n FROM " sleep "generate_series($1::int4, $2::int4) g") [a b]])))

(defn- errors
  "Rows a..b-1, then division by zero, as [sql params]."
  [a b]
  (if (< (rand) 0.5)
    [(str "SELECT (10 / (g - " b "))::int4 * 0 + g AS n FROM generate_series(" a ", " b ") g") nil]
    ["SELECT (10 / (g - $2))::int4 * 0 + g AS n FROM generate_series($1::int4, $2::int4) g" [a b]]))

(defn- js-args
  "Arguments for node/query and node/stream."
  [[sql params]]
  [sql (some-> params clj->js)])

(defn- clj-sql
  "The sql argument of clunk.core/query."
  [[sql params]]
  (if params (into [sql] params) sql))

(defn- fetch-opts
  "Portal batch sizes from small to the default."
  []
  {:fetch-size (rand-nth [nil 10 100 1000])})

(def ^:private texts ["" "plain" "naïve café" "日本語のテキスト" "emoji 🐘🚀" "quote ' and \\ back" "tab\tnew\nline"])

(defn- rand-range []
  (let [a (rand-int 1000000)
        n (if (< (rand) 0.1) (+ 5000 (rand-int 20000)) (rand-int 300))]
    [a (+ a n)]))

(defn- ns-of [rows] (mapv #(if (map? %) (:n %) (.-n ^js %)) rows))

(defn- exactly? [rows a b] (= (ns-of rows) (range a (inc b))))

(defn- prefix? [rows a] (= (ns-of rows) (range a (+ a (count rows)))))

(defn- in-order?
  "After a cancel, query's own drain takes rows too, so the rows a reader
   sees can skip some, but they stay in order within a..b."
  [rows a b]
  (apply < (dec a) (concat (ns-of rows) [(inc b)])))

(defn- killed? [e]
  (or (= "57P01" (or (.-code e) (:code (ex-data e))))
      (boolean (re-find #"Connection closed" (str (or (ex-message e) (.-message e)))))))

(defn- settle
  "A channel that yields [:ok value] or [:err error] once promise settles.
   No timeouts: a hang shows as an op that stays in flight, which the
   watcher reports."
  [promise]
  (let [ch (async/promise-chan)]
    (.then promise #(async/put! ch [:ok %]) #(async/put! ch [:err %]))
    ch))

(defn- error-result [e code check]
  (cond (killed? e)            :killed
        (not= code (.-code e)) {:fail (str "rejected " (.-code e) " " (.-message e))}
        (check)                :ok
        :else                  {:fail "wrong rows before the error"}))

(defn- take-rows
  "Reads up to m rows from a stream iterator. A channel of [rows done? error]."
  [it m]
  (async/go
    (loop [acc []]
      (if (>= (count acc) m)
        [acc false nil]
        (let [[tag v] (async/<! (settle (.next it)))]
          (if (= :err tag)
            [acc true v]
            (if (.-done v) [acc true nil] (recur (conj acc (.-value v))))))))))

(defn- take-chan
  "Takes up to m rows from ch, fewer if it closes first. A channel of them."
  [ch m]
  (async/go
    (loop [acc []]
      (if-let [v (and (< (count acc) m) (async/<! ch))]
        (recur (conj acc v))
        acc))))

(defn- chan-result [rows a b]
  (cond (some clunk/error? rows) (if (every? #(or (not (clunk/error? %)) (killed? %)) rows) :killed {:fail "error"})
        (exactly? rows a b)      :ok
        (empty? rows)            :cancelled-early
        (in-order? rows a b)     :cancelled-mid
        :else                    {:fail "wrong rows"}))

(defn- stream-result [[rows _ err] ok?]
  (cond err   (if (killed? err) :killed {:fail (str "rejected " (.-message err))})
        (ok? rows) :ok
        :else {:fail "wrong rows"}))

(defn- run-op [p kind]
  (let [[a b] (rand-range)]
    (async/go
      (case kind
        :query
        (let [[tag v] (async/<! (settle (apply node/query p (js-args (series a b)))))]
          (cond (= :err tag)     (if (killed? v) :killed {:fail (str "rejected " (.-message v))})
                (exactly? v a b) (do (swap! rows-read + (count v)) :ok)
                :else            {:fail "wrong rows"}))

        :query-params
        ;; Text through parameters both ways, so UTF-8 sizing is in the mix.
        (let [t       (rand-nth texts)
              [tag v] (async/<! (settle (node/query p "SELECT $1::text AS s, $2::int4 AS n, length($1::text)::int4 AS len" #js [t a])))]
          (cond (= :err tag) (if (killed? v) :killed {:fail (str "rejected " (.-message v))})
                (= [{"s" t "n" a "len" (count (js/Array.from t))}] (js->clj v)) :ok
                :else        {:fail (str "wrong row " (js/JSON.stringify v))}))

        :query-error
        (let [[[sql params] code] (if (< (rand) 0.5) [["SELECT * FROM no_such_table" nil] "42P01"] [(errors a b) "22012"])
              [tag v]             (async/<! (settle (node/query p sql (some-> params clj->js))))]
          (if (= :err tag)
            (error-result v code (constantly true))
            {:fail "no error"}))

        :stream
        (let [r (async/<! (take-rows (apply node/stream p (js-args (series a b))) js/Infinity))]
          (stream-result r #(when (exactly? % a b) (swap! rows-read + (count %)) true)))

        :stream-break
        (let [it (apply node/stream p (js-args (series a b)))
              r  (async/<! (take-rows it (rand-int 100)))]
          ;; What for-await does on break.
          (.return it)
          (stream-result r #(prefix? % a)))

        :stream-error
        (let [[rows _ err] (async/<! (take-rows (apply node/stream p (js-args (errors a b))) js/Infinity))]
          (if err
            (error-result err "22012" #(exactly? rows a (dec b)))
            {:fail "no error"}))

        :cancel-promise
        (let [cancel (async/promise-chan)
              ch     (clunk/query p (clj-sql (series a b)) (assoc (fetch-opts) :cancel cancel))
              head   (async/<! (take-chan ch (rand-int 100)))]
          (async/close! cancel)
          (let [tail (async/<! (take-chan ch js/Infinity))]
            (cond (some clunk/error? (concat head tail))
                  (if (every? #(or (not (clunk/error? %)) (killed? %)) (concat head tail)) :killed {:fail "error"})
                  (and (prefix? head a) (in-order? tail (+ a (count head)) b)) :ok
                  :else {:fail "wrong rows"})))

        :cancel-timeout
        ;; 1 ms to 20 s, so some fire while the query waits for a connection
        ;; and some while its rows arrive.
        (chan-result (async/<! (take-chan (clunk/query p (clj-sql (series a b))
                                                       (assoc (fetch-opts) :cancel (async/timeout (int (js/Math.pow 10 (rand 4.3))))))
                                          js/Infinity))
                     a b)))))

(def kinds [:query :query :query-params :query-error :stream :stream :stream-break :stream-error :cancel-promise :cancel-timeout :cancel-timeout])

;; ---- measuring

(defn- heap-mb []
  (when js/global.gc (js/global.gc))
  (js/Math.round (/ (.-heapUsed (js/process.memoryUsage)) 1048576)))

(defn- rss-mb [] (js/Math.round (/ (.-rss (js/process.memoryUsage)) 1048576)))

(defn- sockets []
  (count (filter #{"TCPSocketWrap"} (js/process.getActiveResourcesInfo))))

(defn- admin-client []
  (async/go
    (let [c (clunk/client config)]
      (async/<! (clunk/connect c))
      c)))

(defn- invariant-errors [{:keys [idle size waiters closed?]}]
  (cond-> []
    (not (<= 0 size max-size))               (conj (str "size " size))
    (> (count idle) size)                    (conj "more idle than size")
    (and (seq idle) (seq waiters))           (conj "idle and waiters at once")
    (and (seq waiters) (not= size max-size)) (conj "waiters while not full")
    (not= (count idle) (count (set (map goog/getUid idle)))) (conj "a client idle twice")
    (and closed? (seq waiters))              (conj "waiters after close")))

(defn- backends [admin admin-pids]
  (async/go
    (:n (first (clunk/<query! admin (str "SELECT count(*)::int4 AS n FROM pg_stat_activity"
                                         " WHERE usename = 'jimmy' AND datname = 'world'"
                                         " AND backend_type = 'client backend'"
                                         " AND pid NOT IN (" (apply str (interpose ", " admin-pids)) ")"))))))

(defn main [& args]
  (let [seconds   (js/parseInt (or (first args) "180"))
        n-workers (js/parseInt (or (second args) "2000"))
        kinds     (if-let [only (nth args 2 nil)] (mapv keyword (.split only ",")) kinds)]
    (async/go
      (let [killer     (async/<! (admin-client))
            watcher    (async/<! (admin-client))
            admins     [(:pid (first (clunk/<query! killer "SELECT pg_backend_pid() AS pid")))
                        (:pid (first (clunk/<query! watcher "SELECT pg_backend_pid() AS pid")))]
            base       {:heap-mb (heap-mb) :rss-mb (rss-mb) :sockets (sockets)}
            p          (node/pool (clj->js (assoc config :maxSize max-size)))
            state      (:state p)
            violations (atom [])
            _          (add-watch state ::invariants
                                  (fn [_ _ _ s]
                                    (when-let [errs (seq (invariant-errors s))]
                                      (swap! violations #(if (< (count %) 20) (conj % [errs (dissoc s :idle :waiters)]) %)))))
            counts     (atom {})
            failures   (atom [])
            inflight   (atom {})
            total      (atom 0)
            stop?      (atom false)
            peak       (atom 0)
            kills      (atom {:idle 0 :active 0})
            samples    (atom [])
            start      (js/Date.now)
            elapsed    #(/ (- (js/Date.now) start) 1000)
            workers    (doall
                        (for [i (range n-workers)]
                          (async/go-loop []
                            (when-not @stop?
                              (let [kind (rand-nth kinds)]
                                (swap! inflight assoc i [kind (js/Date.now)])
                                (let [r (async/<! (run-op p kind))]
                                  (swap! inflight dissoc i)
                                  (swap! total inc)
                                  (swap! counts update-in [kind (if (map? r) :fail r)] (fnil inc 0))
                                  (when (map? r)
                                    (swap! failures #(if (< (count %) 50) (conj % [kind r]) %)))))
                              (recur)))))
            kill-loop  (async/go-loop [want "idle"]
                         (when-not @stop?
                           (async/<! (async/timeout (+ 300 (rand-int 700))))
                           (let [pids (map :pid (clunk/<query! killer (str "SELECT pid FROM pg_stat_activity"
                                                                           " WHERE usename = 'jimmy' AND datname = 'world'"
                                                                           " AND backend_type = 'client backend'"
                                                                           " AND state = '" want "'"
                                                                           " AND pid NOT IN (" (apply str (interpose ", " admins)) ")")))]
                             (when-let [pid (first (shuffle pids))]
                               (clunk/<query! killer (str "SELECT pg_terminate_backend(" pid ")"))
                               (swap! kills update (keyword want) inc)))
                           (recur (if (= want "idle") "active" "idle"))))
            watch-loop (async/go-loop [i 0]
                         (when-not @stop?
                           (swap! peak max (async/<! (backends watcher admins)))
                           (when (zero? (mod i 50))
                             (let [s    @state
                                   line {:t (js/Math.round (elapsed)) :queries @total
                                         :qps (js/Math.round (/ @total (max 1 (elapsed))))
                                         :size (:size s) :idle (count (:idle s)) :waiters (count (:waiters s))
                                         :peak-backends @peak :sockets (sockets) :rss-mb (rss-mb)
                                         :oldest-ms (reduce max 0 (map #(- (js/Date.now) (second %)) (vals @inflight)))}
                                   line (if (zero? (mod i 300)) (assoc line :heap-mb (heap-mb)) line)]
                               (swap! samples conj line)
                               (prn line)))
                           (async/<! (async/timeout 100))
                           (recur (inc i))))]
        (async/<! (async/timeout (* 1000 seconds)))
        (reset! stop? true)
        (let [run-secs (elapsed)
              ;; Ops still in flight hang-ms after the stop count as hung.
              _        (async/alts! [(async/into [] (async/merge workers)) (async/timeout hang-ms)])
              hung     @inflight
              _        (async/<! kill-loop)
              _        (async/<! watch-loop)
              _        (async/<! (async/timeout 1000))
              s        @state
              before-close {:size (:size s) :idle (count (:idle s)) :waiters (count (:waiters s))
                            :backends (async/<! (backends watcher admins))}
              _        (node/close p)
              s2       @state
              gone     (loop [i 0]
                         (let [n (async/<! (backends watcher admins))]
                           (if (or (zero? n) (> i 50)) n (do (async/<! (async/timeout 100)) (recur (inc i))))))
              _        (async/<! (async/timeout 500))
              after    {:heap-mb (heap-mb) :rss-mb (rss-mb) :sockets (sockets)}
              n-fail   (reduce + (keep :fail (vals @counts)))
              report   {:seconds        (js/Math.round run-secs)
                        :workers        n-workers
                        :queries        @total
                        :per-second     (js/Math.round (/ @total run-secs))
                        :full-read-rows-per-second (js/Math.round (/ @rows-read run-secs))
                        :by-kind        (into (sorted-map) @counts)
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
                        :rss-mb-samples (keep :rss-mb @samples)
                        :end            after}]
          (println)
          (pprint report)
          (clunk/close killer)
          (clunk/close watcher)
          (js/process.exit (if (and (zero? n-fail) (empty? hung) (empty? @violations)
                                    (<= @peak max-size) (zero? gone) (zero? (:size s2))
                                    (zero? (:waiters before-close)) (= (:size before-close) (:idle before-close)))
                             0 1)))))))
