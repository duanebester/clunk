(ns clunk.core
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            #?(:clj  [clojure.core.async.impl.protocols :as async-impl]
               :cljs [cljs.core.async.impl.protocols :as async-impl])
            [clunk.pw :as pw]
            [clunk.oid-types :as oid-types]
            [clunk.vendor.binf :as binf]
            [clunk.message-socket :as ms])
  #?(:clj (:import (java.util.concurrent CompletableFuture)
                   (java.util.function Function)))
  #?(:cljs (:require-macros [clunk.core])))

(defprotocol Queryable
  "What query, execute and plan run sql on: a Client, or a pool of clients
   (clunk.pool)."
  (submit [this request]
    "Queues request, a map with :sql, :params, :fetch-size, :sink (where its
     rows go; see Sink), :cancel (the caller's cancel channel, or nil) and
     :cancelled (an atom that turns true once query's watcher has seen cancel
     fire), to run on a connection. Ends the sink at once if it can't. A
     request's optional :done function is called once the query has finished
     on its connection.")
  (close [this]
    "Closes the connection, or every connection of a pool.")
  (listener [this]
    "The client that listen and unlisten use: a client itself, or a pool's
     connection for listeners, which it never lends to queries. Returns an
     error value if it can't connect."))

(defn with-lock
  "Calls f holding lock on the JVM. JS has one thread, so it just calls it."
  [lock f]
  #?(:clj  (locking lock (f))
     :cljs (f)))

(defprotocol Sink
  "Where a query's rows go. The connection calls these in order, on the JVM
   from its reader thread, and ends every sink exactly once. Each returns
   false once the reader has gone, so the connection can stop the server."
  (-row! [sink ms row] "ms is the message socket the query runs on, or nil.")
  (-flush! [sink ms] "Hands over rows held back so far.")
  (-error! [sink ms err])
  (-end! [sink ms]))

(def ^:private empty-queue #?(:clj clojure.lang.PersistentQueue/EMPTY :cljs cljs.core/PersistentQueue.EMPTY))

(defn- error [msg data]
  (ex-info msg data))

(defn error?
  "True for the error values connect and query put on their channels."
  [x]
  (instance? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) x))

(defn fail!
  "Ends a request that won't run, with err unless it's nil."
  [{:keys [sink done]} err]
  (when err (-error! sink nil err))
  (-end! sink nil)
  (when done (done)))

(defn cancelled?
  "True once request's :cancel has fired: closed, or holding a value. Taking
   that value means query's watcher never sees it, so the caller must end the
   request itself."
  [{:keys [cancel cancelled]}]
  (or @cancelled
      (and cancel (or (async-impl/closed? cancel) (some? (async/poll! cancel))))))

;; ---- the connection's protocol state machine
;;
;; A Client owns one connection. The socket calls on-message with each backend
;; message as it arrives (on the JVM, on the connection's reader thread; in JS,
;; from the socket's data events), so nothing waits between the socket and a
;; query's rows. :state holds what other threads touch, changed only by swaps
;; that decide from the state they replaced:
;;   :phase   :new, :startup (authenticating), :ready, :failed or :closed
;;   :busy?   true while a query is on the connection
;;   :queue   requests waiting for it
;;   :failure the error for requests once :failed
;; :query holds the running query, a Query, or nil. Whoever starts a query
;; sets it before sending; after that only the reader touches it, except that
;; on-close and a start! that lost the race with it each take it with a swap,
;; so exactly one of them ends it.

;; The running query. live, synced and decode are volatiles, read and set on
;; every message, which is why this is a type and not a map: a DataRow costs
;; field reads, not keyword lookups.
;;   live    false once the sink refuses rows; the rest are read and dropped
;;   synced  true once Sync is sent; the server then ends with ReadyForQuery
;;   decode  the row decoder from the last RowDescription
(deftype Query [request sink sql fetch msock live synced decode])

(defn- decode-value
  "Reads one value of field's column with decode, or throws an error naming
   the column, its type OID and sql."
  [decode view len field sql]
  (try (decode view len)
       (catch #?(:clj Exception :cljs :default) e
         (throw (ex-info (str "Could not decode column " (:field-name field) " (type " (:oid field) "): " (ex-message e))
                         {:column (:field-name field) :oid (:oid field) :sql sql}
                         e)))))

(defn- row-decoder
  "From a RowDescription's fields, a function that reads a DataRow's view
   straight into a row map, or into an error if a value fails to decode. Keys
   and per-column decoders are built once here, not once per row."
  [fields sql]
  (let [fs       (object-array fields)
        ks       (object-array (map (comp keyword :field-name) fields))
        decoders (object-array (map (comp oid-types/decoder :oid) fields))
        n        (count fields)]
    (fn [view]
      (binf/rr-i16 view) ;; column count, same as the RowDescription's
      (try
        (loop [i 0
               row (transient {})]
          (if (< i n)
            (let [len (binf/rr-i32 view)] ;; -1 is SQL NULL
              (recur (inc i)
                     (assoc! row (aget ks i)
                             (when (<= 0 len) (decode-value (aget decoders i) view len (aget fs i) sql)))))
            (persistent! row)))
        (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
          e)))))

(defn- start!
  "Sends request, which now has the connection. A query with params or a
   fetch size goes through the extended protocol. With a fetch size, each
   Execute asks for that many rows, and the next batch is asked for only while
   the sink wants more, so a reader that leaves also stops the server.
   Otherwise sql goes as a simple Query, which can hold several statements."
  [{:keys [message-socket query state]} {:keys [sql params fetch-size sink] :as request}]
  (let [batched? (pos? fetch-size)
        q        (Query. request sink sql fetch-size message-socket
                         (volatile! true) (volatile! (not batched?)) (volatile! nil))]
    (reset! query q)
    ;; The connection may have ended after this request took it and before
    ;; query was set, in which case on-close found no query to end. Ending it
    ;; here, unless on-close took it after all, keeps it from waiting forever.
    (let [{:keys [phase failure]} @state]
      (if (#{:failed :closed} phase)
        (when (compare-and-set! query q nil)
          (fail! request (when (= :failed phase) (failure request))))
        (ms/send! message-socket
                  (if (or params batched?)
                    [{:type :Parse :query sql}
                     {:type :Bind :params params}
                     {:type :Describe}
                     {:type :Execute :max-rows fetch-size}
                     ;; Sync would end the implicit transaction, and the portal with it.
                     {:type (if batched? :Flush :Sync)}]
                    {:type :Query :query sql}))))))

(defn- next-request!
  "The connection is free: takes the next queued request, or marks it idle."
  [state]
  (let [[old] (swap-vals! state (fn [{:keys [queue] :as s}]
                                  (if (seq queue) (update s :queue pop) (assoc s :busy? false))))]
    (peek (:queue old))))

(defn- finish!
  "The server is ready again after q's query: frees the connection, then ends
   the query, so a pool can lend it again at once."
  [{:keys [query state message-socket] :as client} ^Query q]
  (reset! query nil)
  (let [next (next-request! state)
        done (:done (.-request q))]
    (when done (done))
    (-end! (.-sink q) message-socket)
    (when next (start! client next))))

(defn- on-row
  "A DataRow for the running query q, decoded straight to its sink. Once the
   sink refuses rows, the rest are dropped."
  [^Query q message]
  (when @(.-live q)
    (let [sink (.-sink q)
          ms   (.-msock q)
          row  (@(.-decode q) (:view message))]
      ;; A row that fails to decode fails the query like a server error.
      (when-not (if (error? row)
                  (and (-error! sink ms row) false)
                  (-row! sink ms row))
        (vreset! (.-live q) false)))))

(defn- on-query-message
  "Every message of the running query but its DataRows: streams it until the
   server is ready again."
  [{:keys [message-socket query] :as client} message]
  (when-let [^Query q @query]
    (if (= :RowDescription (:type message))
      (vreset! (.-decode q) (row-decoder (:values message) (.-sql q)))
      ;; Any other message ends a run of rows, so they go to the reader first.
      (let [sink   (.-sink q)
            live?  (and @(.-live q) (-flush! sink message-socket))
            synced (.-synced q)]
        (vreset! (.-live q) live?)
        (case (:type message)
          :ReadyForQuery   (finish! client q)
          :PortalSuspended (do (ms/send! message-socket (if live?
                                                          [{:type :Execute :max-rows (.-fetch q)} {:type :Flush}]
                                                          [{:type :Close} {:type :Sync}]))
                               (vreset! synced (not live?)))
          (:CommandComplete :EmptyQueryResponse)
          (do (when-not @synced (ms/send! message-socket {:type :Sync}))
              (vreset! synced true))
          ;; After an error the server skips to the next Sync.
          :ErrorMessage    (do (when-not @synced (ms/send! message-socket {:type :Sync}))
                               (vreset! synced true)
                               (when live?
                                 (vreset! (.-live q) (boolean (-error! sink message-socket (error (:message message) (assoc message :sql (.-sql q))))))))
          nil)))))

(defn- fail-startup!
  "Authentication failed: connect yields err, and so does every query."
  [{:keys [state ready]} err]
  (let [[old] (swap-vals! state assoc :phase :failed :failure (constantly err) :queue empty-queue)]
    (async/put! @ready err)
    (run! #(fail! % err) (:queue old))))

(defn- on-startup-message
  "Answers the server's auth requests until it is ready for queries."
  [{:keys [message-socket config state ready scram] :as client} message]
  (let [{:keys [user password debug]} config
        send #(ms/send! message-socket %)]
    (when debug
      (println message))
    (try
      (case (:type message)
        :ReadyForQuery
        (let [[old] (swap-vals! state (fn [{:keys [queue] :as s}]
                                        (if (seq queue)
                                          (assoc s :phase :ready :busy? true :queue (pop queue))
                                          (assoc s :phase :ready :busy? false))))]
          (async/put! @ready message)
          (when-let [next (peek (:queue old))]
            (start! client next)))

        :ErrorMessage
        (fail-startup! client (error (str "Startup failed: " (:message message)) message))

        :AuthenticationMD5
        (send {:type :PasswordMessage :password (pw/calculate-pw user password (:salt message))})

        :AuthenticationSASL
        (if (some #{"SCRAM-SHA-256"} (:mechanisms message))
          (let [first-msg (vreset! scram (pw/scram-client-first))]
            (send {:type :SASLInitialResponse :mechanism "SCRAM-SHA-256" :data (:message first-msg)}))
          (fail-startup! client (error "Unsupported SASL mechanisms" message)))

        :AuthenticationSASLContinue
        (let [final-msg (vreset! scram (pw/scram-client-final (assoc @scram :password password :server-first (:data message))))]
          (send {:type :SASLResponse :data (:message final-msg)}))

        :AuthenticationSASLFinal
        (when-not (pw/scram-server-final-valid? @scram (:data message))
          (fail-startup! client (error "SCRAM server signature mismatch" message)))

        nil)
      (catch #?(:clj Exception :cljs :default) e
        (fail-startup! client (error (str "Startup failed: " (ex-message e)) {}))))))

(declare on-notification close-listeners!)

(defn- on-message
  "Handles every message the server sends, whenever it sends it: during a
   query, between queries, or while the connection is idle. Notifications go
   to listeners; a ParameterStatus or NoticeResponse outside startup changes
   nothing."
  [client message]
  (case (:type message)
    ;; The hot path: no phase check, since the server sends no rows during startup.
    :DataRow              (when-let [q @(:query client)] (on-row q message))
    :NotificationResponse (on-notification client message)
    (if (= :startup (:phase @(:state client)))
      (on-startup-message client message)
      (on-query-message client message))))

(defn- on-chunk-end
  "A socket read has been handled: rows held back go to the reader now,
   rather than waiting for a batch to fill."
  [{:keys [query message-socket]}]
  (when-let [^Query q @query]
    (when (and @(.-live q) (not (-flush! (.-sink q) message-socket)))
      (vreset! (.-live q) false))))

(defn- on-close
  "The connection ended, whether the server hung up or close was called: the
   running query and every queued one fail, and so does every later one."
  [{:keys [state query] :as client}]
  (let [closed (fn [{:keys [sql]}] (error "Connection closed during query" {:sql sql}))
        [old]  (swap-vals! state (fn [{:keys [phase] :as s}]
                                   (cond-> (assoc s :queue empty-queue)
                                     (#{:new :startup :ready} phase) (assoc :phase :failed :failure closed))))
        cause  (ms/closed-by (:message-socket client))
        err    (ex-info (str "Connection closed during startup" (some->> cause ex-message (str ": "))) {} cause)]
    ;; A no-op if connect already yielded.
    (async/put! @(:ready client) err)
    (when (#{:new :startup} (:phase old))
      (run! #(fail! % err) (:queue old)))
    ;; A swap, as start! may race to end the query it just set; see start!.
    (when-let [^Query q (first (reset-vals! query nil))]
      (fail! (.-request q) (when @(.-live q) (closed (.-request q)))))
    (when (= :ready (:phase old))
      (run! #(fail! % (closed %)) (:queue old)))
    (when (= :closed (:phase old))
      (run! #(fail! % nil) (:queue old)))
    (close-listeners! client (when-not (= :closed (:phase old))
                               (error "Connection closed" {})))))

(defrecord Client [message-socket config state query ready scram listeners]
  Queryable
  (submit [this request]
    (let [[old] (swap-vals! state (fn [{:keys [phase busy?] :as s}]
                                    (cond (#{:failed :closed} phase)       s
                                          (and (= :ready phase) (not busy?)) (assoc s :busy? true)
                                          :else                           (update s :queue conj request))))]
      (case (:phase old)
        :failed (fail! request ((:failure old) request))
        :closed (fail! request nil)
        (when (and (= :ready (:phase old)) (not (:busy? old)))
          (start! this request)))))
  (close [this]
    (swap! state assoc :phase :closed)
    (ms/close-message-socket message-socket)
    this)
  (listener [this] this))

(defn- ssl-config
  "The TLS settings in config, in the form clunk.tls takes them, or nil for a
   plain connection. :ssl is a mode named as libpq's sslmode is:
     :disable      plain (the default; also nil or false)
     :prefer       TLS if the server offers it, else plain
     :require      TLS, without checking the server's certificate
     :verify-ca    TLS, with a certificate signed by a trusted CA
     :verify-full  TLS, with a trusted certificate for the server's name (also true)
   :prefer and :require check the certificate as :verify-ca does when
   :ssl-root-cert is given, as libpq does when it has a root certificate.
   :ssl-negotiation :direct starts TLS at once, without asking first, which
   saves a round trip but needs Postgres 17 or later."
  [config host]
  (let [ssl         (get config :ssl :disable)
        mode        (case ssl (nil false) :disable true :verify-full (keyword ssl))
        negotiation (keyword (get config :ssl-negotiation :postgres))]
    (when-not (#{:disable :prefer :require :verify-ca :verify-full} mode)
      (throw (ex-info (str "Unknown :ssl mode " (pr-str ssl)) {:ssl ssl})))
    (when-not (#{:postgres :direct} negotiation)
      (throw (ex-info (str "Unknown :ssl-negotiation " (pr-str negotiation)) {:ssl-negotiation negotiation})))
    (when (and (= :direct negotiation) (= :prefer mode))
      (throw (ex-info ":ssl-negotiation :direct needs :ssl :require or stronger" {:ssl ssl})))
    (when-not (= :disable mode)
      {:mode        mode
       :negotiation negotiation
       ;; The name sent for SNI and checked by :verify-full.
       :servername  (get config :ssl-servername host)
       ;; Each a file path, PEM text, or on Node a Buffer.
       :root-cert   (:ssl-root-cert config)
       :cert        (:ssl-cert config)
       :key         (:ssl-key config)
       ;; Passed through: a javax.net.ssl.SSLContext, which replaces
       ;; :ssl-root-cert, :ssl-cert and :ssl-key, or Node tls.connect options,
       ;; which are merged over clunk's own.
       :context     (:ssl-context config)
       :options     (:ssl-options config)})))

(defn client [config]
  (let [host     (get config :hostname "localhost")
        port     (get config :port 5432)
        user     (get config :username "postgres")
        password (get config :password "")
        database (get config :database "postgres")
        debug    (get config :debug false)
        cfg      {:debug debug :host host :port port :user user :password password :database database}
        c        (map->Client {:message-socket (ms/get-message-socket port host (ssl-config config host))
                               :config         cfg
                               :state          (atom {:phase :new :busy? false :queue empty-queue})
                               :query          (atom nil)
                               :ready          (volatile! (async/promise-chan))
                               :scram          (volatile! nil)
                               ;; LISTEN channel name -> the listeners on it; see listen.
                               :listeners      (atom {})})]
    (ms/start! (:message-socket c) #(on-message c %) #(on-chunk-end c) #(on-close c))
    c))

(defn connect
  "Starts the connection: authenticates, then serves queued queries one at a
   time. Returns a channel that yields the server's ReadyForQuery message, or
   an error, and then closes. Queries made before it is ready wait their turn."
  [{:keys [state ready message-socket config] :as client}]
  (let [[old] (swap-vals! state (fn [s] (cond-> s (= :new (:phase s)) (assoc :phase :startup))))]
    (case (:phase old)
      :new    (ms/send! message-socket {:type :StartupMessage :user (:user config) :database (:database config)})
      :failed (async/put! @ready ((:failure old) {}))
      nil)
    @ready))

;; ---- sinks

(def ^:private batch-size
  "Most rows a channel sink puts on its channel at once. The channel takes a
   batch only once its reader has taken every row of the last one, so with
   one more batch waiting, at most 64 rows are read ahead of the reader. That
   keeps a query that stops early from asking the server for another
   fetch-size batch."
  32)

(defn- put-rows!
  "Puts a batch of rows on out, and returns false if out is closed. On the
   JVM it blocks the reader thread until out takes it, which pushes back on
   the server. In JS it can't block, so it pauses the socket until out takes
   every batch put."
  [ms pending out rows]
  #?(:clj  (async/>!! out rows)
     :cljs (let [state (volatile! :putting)
                 open? (async/put! out rows (fn [_]
                                              (if (= :putting @state)
                                                (vreset! state :taken)
                                                (when (zero? (vswap! pending dec))
                                                  (some-> ms ms/resume)))))]
             (when (= :putting @state)
               (vreset! state :waiting)
               (when (= 1 (vswap! pending inc))
                 (some-> ms ms/pause)))
             open?)))

(deftype ChanSink [out pending ^:unsynchronized-mutable rows]
  Sink
  (-row! [this ms row]
    (set! rows (conj! rows row))
    (if (< (count rows) batch-size) true (-flush! this ms)))
  (-flush! [_ ms]
    (if (zero? (count rows))
      (not (async-impl/closed? out))
      (let [batch (persistent! rows)]
        (set! rows (transient []))
        (put-rows! ms pending out batch))))
  (-error! [this ms err]
    (and (-flush! this ms) (put-rows! ms pending out [err])))
  (-end! [this ms]
    (-flush! this ms)
    (async/close! out)))

(defn- chan-sink [out]
  (ChanSink. out (volatile! 0) (transient [])))

(deftype Collector [ok fail ^:unsynchronized-mutable rows ^:unsynchronized-mutable err]
  Sink
  (-row! [_ _ row] (set! rows (conj! rows row)) true)
  (-flush! [_ _] true)
  (-error! [_ _ e] (when-not err (set! err e)) true)
  (-end! [_ _] (if err (fail err) (ok (persistent! rows)))))

(def ^:private default-fetch-size
  "Rows per batch for a query that has a :cancel channel."
  1000)

(defn- request [sql opts]
  (merge {:sql       (if (string? sql) sql (first sql))
          :params    (when-not (string? sql) (mapv oid-types/param-text (rest sql)))
          :cancelled (atom false)}
         opts))

(defn query
  "Queues a query on a client or a pool and returns a channel of row maps,
   keyed by column name, that closes after the last row. A failed query puts
   an error on the channel.

   sql is a string, or a vector of a statement and the values of its
   parameters $1, $2, ..., as in [\"SELECT * FROM city WHERE id = $1\" 42].
   A string with no parameters can hold several statements.

   To stop early, pass a :cancel channel, such as a promise-chan or a timeout,
   and close it or put on it: the returned channel closes and the client
   discards the rest of the result. A query still waiting for a pool's
   connection doesn't run at all. Closing the returned channel alone can
   leave the client waiting to put a row, holding up every later query.

   With :cancel, the server sends the rows in batches of :fetch-size (1000
   unless given), so cancelling also stops the server after the current
   batch. Such a query, like one with parameters, is one statement."
  ([client sql] (query client sql nil))
  ([client sql {:keys [cancel fetch-size]}]
   (let [out (async/chan 1 cat) ;; takes batches of rows; see batch-size
         req (request sql {:fetch-size (or fetch-size (if cancel default-fetch-size 0))
                           :sink       (chan-sink out)
                           :cancel     cancel})]
     (when cancel
       ;; A put already parked on out outlives close!, so take until out is
       ;; empty; the next put is refused and the client stops sending rows.
       (async/go
         (async/<! cancel)
         (reset! (:cancelled req) true)
         (async/close! out)
         (loop [] (when (async/<! out) (recur)))))
     (submit client req)
     out)))

(defn execute
  "Runs sql on a client or a pool, sql being a string or a vector of a
   statement and its parameters as for query, and returns all its rows as a
   vector of maps: a CompletableFuture on the JVM (deref it with @) and a
   js/Promise in ClojureScript. A failed query fails it with the error.

   On the JVM the future completes on the connection's reader thread, so a
   function given to thenApply, whenComplete and the like runs there, and the
   connection reads nothing more until it returns. It must not block, and
   must never wait for another query on the same client or pool: a pool may
   lend that very connection to the query, which then waits forever. The
   ...Async variants run it on another thread."
  [client sql]
  #?(:clj  (let [cf (CompletableFuture.)]
             (submit client (request sql {:fetch-size 0 :sink (Collector. #(.complete cf %) #(.completeExceptionally cf %) (transient []) nil)}))
             cf)
     :cljs (js/Promise. (fn [ok fail]
                          (submit client (request sql {:fetch-size 0 :sink (Collector. ok fail (transient []) nil)}))))))

#?(:clj
   (defn plan
     "Returns a reducible that runs sql each time it is reduced and hands each
      row to the reducing function as it arrives, so the rows are never all in
      memory. Works with reduce, transduce, into and run!; a reduced value, as
      from take, stops early. A failed query throws its error. It blocks the
      reducing thread, so don't reduce it inside a go block. On a pool, the
      reduce holds one connection until it returns.

      sql is a string or a vector of a statement and its parameters, as for
      query. The server sends rows in batches of :fetch-size (default 1000),
      so stopping early stops the server too."
     ([client sql] (plan client sql nil))
     ([client sql {:keys [fetch-size]}]
      (reify clojure.lang.IReduceInit
        (reduce [_ f init]
          (let [out (async/chan 1 cat)]
            (submit client (request sql {:fetch-size (or fetch-size default-fetch-size)
                                         :sink       (chan-sink out)}))
            (try
              (loop [acc init]
                (let [row (async/<!! out)]
                  (cond
                    (nil? row)   acc
                    (error? row) (throw row)
                    :else        (let [acc (f acc row)]
                                   (if (reduced? acc) @acc (recur acc))))))
              (finally
                ;; A put already waiting outlives close!, so take until out is
                ;; empty; the next put is refused and the client stops the server.
                (async/close! out)
                (loop [] (when (async/poll! out) (recur)))))))))))

(defmacro <query!
  "Inside a go block, parks until the query finishes and returns a vector of
   all its rows (and any error)."
  [client sql]
  (if (:ns &env)
    `(cljs.core.async/<! (cljs.core.async/into [] (query ~client ~sql)))
    `(clojure.core.async/<! (clojure.core.async/into [] (query ~client ~sql)))))

;; ---- LISTEN and NOTIFY
;;
;; A client's :listeners maps each channel name it listens on to its
;; listeners, each {:ch ch :buf buf :max-queued n}. The reader puts each
;; notification on every listener of its channel with offer!, so it never
;; waits for one. LISTEN and UNLISTEN are queries of their own, sent as the
;; first listener of a name comes and the last goes, under a lock so they go
;; out in the order those changes were made.

(defn- quote-ident
  "name as a quoted SQL identifier, so any name is taken as it is."
  [name]
  (str "\"" (str/replace name "\"" "\"\"") "\""))

(defn- run-sql!
  "Runs sql on client for its effect, calling on-error with the error if it fails."
  ([client sql] (run-sql! client sql nil))
  ([client sql on-error]
  (submit client (request sql {:fetch-size 0
                               :sink (Collector. (fn [_]) (or on-error (fn [_])) (transient []) nil)}))))

(defn- end-listener!
  "Closes l's channel, after err if there is one. The extra slot in its
   buffer always has room for err."
  [l err]
  (when err (async/offer! (:ch l) err))
  (async/close! (:ch l)))

(defn- remove-listeners!
  "Removes the listeners of channel-name that keep? doesn't keep and ends
   them with err. If none are left, sends UNLISTEN with run (run-sql! or
   execute) and returns what it returns."
  [{:keys [listeners] :as client} channel-name keep? err run]
  (with-lock listeners
    (fn []
      (let [[old new] (swap-vals! listeners
                                  (fn [m]
                                    (let [kept (filterv keep? (get m channel-name))]
                                      (if (seq kept) (assoc m channel-name kept) (dissoc m channel-name)))))]
        (run! #(end-listener! % err) (remove keep? (get old channel-name)))
        (when (and (contains? old channel-name) (not (contains? new channel-name)))
          (run client (str "UNLISTEN " (quote-ident channel-name))))))))

(defn- on-notification
  "Puts a notification on each listener of its channel. A listener that has
   max-queued notifications waiting gets an error instead and is closed, and
   one whose channel was closed is dropped, so neither holds up the reader."
  [{:keys [listeners] :as client} {:keys [channel payload pid]}]
  (let [n (array-map :channel channel :payload payload :pid pid)]
    (doseq [{:keys [ch buf max-queued] :as l} (get @listeners channel)]
      (cond
        (async-impl/closed? ch)
        (remove-listeners! client channel #(not= l %) nil run-sql!)

        (<= max-queued (count buf))
        (remove-listeners! client channel #(not= l %)
                           (error "Listener fell behind" {:channel channel :max-queued max-queued})
                           run-sql!)

        :else
        (async/offer! ch n)))))

(defn- close-listeners!
  "The connection is gone: every listener closes, after err if there is one."
  [{:keys [listeners]} err]
  (let [[old] (swap-vals! listeners empty)]
    (doseq [l (apply concat (vals old))]
      (end-listener! l err))))

(defn listen
  "Listens for notifications on channel-name, a LISTEN channel, and returns a
   core.async channel of them, each {:channel name :payload text :pid
   notifying-backend-pid}, from the time LISTEN has run.

   On a pool, every listener shares one connection of its own, which the pool
   never lends to queries and which doesn't count toward :max-size.

   The connection never waits for a listener: one with :max-queued (default
   1000) notifications not yet taken gets an error value instead and closes.
   If LISTEN fails, or the connection drops, the channel gets an error value
   and closes. A clunk/close closes it with no error. To stop, call unlisten;
   closing the channel yourself stops it too, as the next notification on
   channel-name arrives. UNLISTEN goes once no listener is left on the name."
  ([client channel-name] (listen client channel-name nil))
  ([client channel-name {:keys [max-queued] :or {max-queued 1000}}]
   (let [c   (listener client)
         buf (async/buffer (inc max-queued)) ;; the last slot is for an error
         ch  (async/chan buf)
         l   {:ch ch :buf buf :max-queued max-queued}]
     (if (error? c)
       (end-listener! l c)
       (with-lock (:listeners c)
         (fn []
           (let [[old] (swap-vals! (:listeners c) update channel-name (fnil conj []) l)]
             (when-not (contains? old channel-name)
               (submit c (request (str "LISTEN " (quote-ident channel-name))
                                  {:fetch-size 0
                                   :sink       (Collector.
                                                ;; A closed client ends a query with no error.
                                                (fn [_]
                                                  (when (#{:failed :closed} (:phase @(:state c)))
                                                    (close-listeners! c nil)))
                                                (fn [err]
                                                  (remove-listeners! c channel-name (constantly false) err run-sql!))
                                                (transient [])
                                                nil)})))))))
     ch)))

(defn unlisten
  "Stops listening: x is a channel listen returned, which closes, or a
   channel name, whose listeners all close. Sends UNLISTEN once no listener
   is left on the name. Returns a CompletableFuture or js/Promise that
   settles once the server has stopped sending them, or nil if there was
   nothing to stop."
  [client x]
  (let [c (listener client)]
    (when-not (error? c)
      (if (string? x)
        (remove-listeners! c x (constantly false) nil execute)
        (when-let [[channel-name] (first (filter (fn [[_ ls]] (some #(= x (:ch %)) ls)) @(:listeners c)))]
          (remove-listeners! c channel-name #(not= x (:ch %)) nil execute))))))

(defn notify
  "Sends payload, a string, to the listeners of channel-name with
   pg_notify, on a client or a pool. Returns a CompletableFuture or
   js/Promise of nil, failed if the server refuses it."
  [client channel-name payload]
  (let [result (execute client ["SELECT pg_notify($1, $2)" channel-name payload])]
    #?(:clj  (.thenApply ^CompletableFuture result (reify Function (apply [_ _] nil)))
       :cljs (.then result (fn [_] nil)))))
