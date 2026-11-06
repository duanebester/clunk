(ns clunk.core
  (:require [clojure.core.async :as async]
            [clunk.pw :as pw]
            [clunk.oid-types :as oid-types]
            [clunk.vendor.binf :as binf]
            [clunk.message-socket :as ms])
  #?(:clj (:import (java.util.concurrent CompletableFuture)))
  #?(:cljs (:require-macros [clunk.core])))

(defprotocol Queryable
  "What query, execute and plan run sql on: a Client, or a pool of clients
   (clunk.pool)."
  (submit [this request]
    "Queues request, a map with :sql, :params, :fetch-size, :out (the row
     channel, which takes vectors of rows or errors and spreads them over its
     buffer), :cancel (the caller's cancel channel, or nil) and :cancelled
     (an atom that turns true once query's watcher has seen cancel fire), to
     run on a connection.
     Closes :out at once if it can't. A request's optional :done function is
     called once the query has finished on its connection.")
  (close [this]
    "Closes the connection, or every connection of a pool."))

;; A Client owns one connection. Requests queue on :requests, and a single
;; session loop (started by connect) is the only reader of the socket, so
;; queries run one at a time in the order they were made.
(defrecord Client [message-socket config requests]
  Queryable
  (submit [_ request]
    (when-not (async/put! requests request)
      (async/close! (:out request))))
  (close [this]
    (async/close! requests)
    (ms/close-message-socket message-socket)
    (assoc this :message-socket nil :requests nil)))

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
        msocket  (ms/get-message-socket port host (ssl-config config host))
        cfg      {:debug debug :host host :port port :user user :password password :database database}]
    (map->Client {:message-socket msocket :config cfg :requests (async/chan 64)})))

(defn- error [msg data]
  (ex-info msg data))

(defn error?
  "True for the error values connect and query put on their channels."
  [x]
  (instance? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) x))

(defn- authenticate
  "Answers the server's auth requests until it is ready for queries. Returns a
   channel that yields the ReadyForQuery message, or an error."
  [{:keys [message-socket config]}]
  (let [{:keys [user password debug]} config
        in  (:in message-socket)
        out (:out message-socket)]
    (async/go
      (try
        (async/>! out {:type :StartupMessage :user user :database (:database config)})
        ;; scram holds the in-flight SCRAM-SHA-256 exchange between auth messages.
        (loop [scram nil]
          (let [message (async/<! in)]
            (when debug
              (println message))
            (case (:type message)
              nil
              (let [cause (ms/closed-by message-socket)]
                (ex-info (str "Connection closed during startup" (some->> cause ex-message (str ": "))) {} cause))

              :ReadyForQuery
              message

              :ErrorMessage
              (error (str "Startup failed: " (:message message)) message)

              :AuthenticationMD5
              (do (async/>! out {:type     :PasswordMessage
                                 :password (pw/calculate-pw user password (:salt message))})
                  (recur scram))

              :AuthenticationSASL
              (if (some #{"SCRAM-SHA-256"} (:mechanisms message))
                (let [first-msg (pw/scram-client-first)]
                  (async/>! out {:type      :SASLInitialResponse
                                 :mechanism "SCRAM-SHA-256"
                                 :data      (:message first-msg)})
                  (recur first-msg))
                (error "Unsupported SASL mechanisms" message))

              :AuthenticationSASLContinue
              (let [final-msg (pw/scram-client-final (assoc scram
                                                            :password password
                                                            :server-first (:data message)))]
                (async/>! out {:type :SASLResponse :data (:message final-msg)})
                (recur final-msg))

              :AuthenticationSASLFinal
              (if (pw/scram-server-final-valid? scram (:data message))
                (recur nil)
                (error "SCRAM server signature mismatch" message))

              (recur scram))))
        ;; The exchange throws on an answer it can't use, such as a SCRAM nonce
        ;; that doesn't extend ours; connect must still get an error.
        (catch #?(:clj Exception :cljs :default) e
          (ex-info (str "Startup failed: " (ex-message e)) {} e))))))

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

(def ^:private batch-size
  "Most rows serve puts on a query's out at once. out takes a batch only once
   its reader has taken every row of the last one, so with one more batch
   parked, at most 64 rows are read ahead of the reader, as when rows were put
   one at a time on a buffer of 64. That keeps a query that stops early from
   asking the server for another fetch-size batch."
  32)

(defn- poll-rows
  "Decodes message, a DataRow, and the DataRows already waiting on in after
   it, up to batch-size rows. Returns the rows and the next message polled,
   or nil if none was. A row that fails to decode ends the rows with its
   error, and nothing after it is polled."
  [in decode-row message]
  (loop [rows (transient [])
         m    message]
    (let [row  (decode-row (:view m))
          rows (conj! rows row)
          m    (when (and (< (count rows) batch-size) (not (error? row))) (async/poll! in))]
      (if (= :DataRow (:type m))
        (recur rows m)
        [(persistent! rows) m]))))

(def ^:private default-fetch-size
  "Rows per batch for a query that has a :cancel channel."
  1000)

(defn- serve
  "Runs the client's queued queries one at a time, all in one go block: sends
   each query and streams its rows to its out until the server is ready
   again, then calls its :done. Errors go to out as values; out is always
   closed. Once out is closed, it reads and discards the rest of the result.

   A query with params or a fetch size goes through the extended protocol.
   With a fetch size, each Execute asks for that many rows, and the next
   batch is asked for only while out is open, so closing out also stops the
   server. Otherwise sql goes as a simple Query, which can hold several
   statements."
  [{:keys [message-socket requests]}]
  (let [in   (:in message-socket)
        send (:out message-socket)]
    (async/go-loop []
      (when-let [{:keys [sql params fetch-size out done]} (async/<! requests)]
        (let [batched? (pos? fetch-size)]
          (async/>! send (if (or params batched?)
                           [{:type :Parse :query sql}
                            {:type :Bind :params params}
                            {:type :Describe}
                            {:type :Execute :max-rows fetch-size}
                            ;; Sync would end the implicit transaction, and the portal with it.
                            {:type (if batched? :Flush :Sync)}]
                           {:type :Query :query sql}))
          ;; live? turns false at the first put that out refuses because it's closed,
          ;; or once a row fails to decode, which fails the query like a server error.
          ;; synced? turns true once Sync is sent; the server then ends with ReadyForQuery.
          ;; polled is a message poll-rows already took from in, or nil.
          (loop [decode-row nil
                 live?      true
                 synced?    (not batched?)
                 polled     nil]
            (let [message (or polled (async/<! in))]
              (case (:type message)
                nil            (do (when live?
                                     (async/>! out [(error "Connection closed during query" {:sql sql})]))
                                   (async/close! out))
                :ReadyForQuery (async/close! out)
                :RowDescription (recur (row-decoder (:values message) sql) live? synced? nil)
                ;; Rows already read go to out together, up to batch-size, so a
                ;; reader parked on out is woken once per batch rather than once
                ;; per row. On the JVM each wake hands it to another thread.
                :DataRow       (if live?
                                 (let [[rows next] (poll-rows in decode-row message)]
                                   (recur decode-row (and (async/>! out rows) (not (error? (peek rows)))) synced? next))
                                 (recur decode-row false synced? nil))
                :PortalSuspended (do (async/>! send (if live?
                                                      [{:type :Execute :max-rows fetch-size} {:type :Flush}]
                                                      [{:type :Close} {:type :Sync}]))
                                     (recur decode-row live? (not live?) nil))
                (:CommandComplete :EmptyQueryResponse)
                (do (when-not synced? (async/>! send {:type :Sync}))
                    (recur decode-row live? true nil))
                ;; After an error the server skips to the next Sync.
                :ErrorMessage  (do (when-not synced? (async/>! send {:type :Sync}))
                                   (recur decode-row (and live? (async/>! out [(error (:message message) (assoc message :sql sql))])) true nil))
                (recur decode-row live? synced? nil)))))
        (when done (done))
        (recur)))))

(defn connect
  "Starts the connection: authenticates, then serves queued queries one at a
   time. Returns a channel that yields the server's ReadyForQuery message, or
   an error, and then closes. Queries made before it is ready wait their turn."
  [^Client client]
  (let [ready (async/promise-chan)]
    (async/go
      (let [result (async/<! (authenticate client))]
        (async/>! ready result)
        (if (error? result)
          (loop []
            (when-let [{:keys [out]} (async/<! (:requests client))]
              (async/>! out [result])
              (async/close! out)
              (recur)))
          (serve client))))
    ready))

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
   (let [out       (async/chan 1 cat) ;; takes batches of rows; see batch-size
         cancelled (atom false)
         request   {:sql        (if (string? sql) sql (first sql))
                    :params     (when-not (string? sql) (mapv oid-types/param-text (rest sql)))
                    :fetch-size (or fetch-size (if cancel default-fetch-size 0))
                    :out        out
                    :cancel     cancel
                    :cancelled  cancelled}]
     (when cancel
       ;; A put already parked on out outlives close!, so take until out is
       ;; empty; serve's next put is refused and it stops sending rows.
       (async/go
         (async/<! cancel)
         (reset! cancelled true)
         (async/close! out)
         (loop [] (when (async/<! out) (recur)))))
     (submit client request)
     out)))

(defn execute
  "Runs sql on a client or a pool, sql being a string or a vector of a
   statement and its parameters as for query, and returns all its rows as a
   vector of maps: a CompletableFuture on the JVM (deref it with @) and a
   js/Promise in ClojureScript. A failed query fails it with the error."
  [client sql]
  (let [settle (fn [ok fail]
                 (async/take! (async/into [] (query client sql))
                              (fn [rows]
                                (if-let [err (first (filter error? rows))]
                                  (fail err)
                                  (ok rows)))))]
    #?(:clj  (let [cf (CompletableFuture.)]
               (settle #(.complete cf %) #(.completeExceptionally cf %))
               cf)
       :cljs (js/Promise. settle))))

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
          (let [cancel (async/promise-chan)
                ch     (query client sql {:cancel cancel :fetch-size fetch-size})]
            (try
              (loop [acc init]
                (let [row (async/<!! ch)]
                  (cond
                    (nil? row)   acc
                    (error? row) (throw row)
                    :else        (let [acc (f acc row)]
                                   (if (reduced? acc) @acc (recur acc))))))
              (finally
                (async/close! cancel)))))))))

(defmacro <query!
  "Inside a go block, parks until the query finishes and returns a vector of
   all its rows (and any error)."
  [client sql]
  (if (:ns &env)
    `(cljs.core.async/<! (cljs.core.async/into [] (query ~client ~sql)))
    `(clojure.core.async/<! (clojure.core.async/into [] (query ~client ~sql)))))
