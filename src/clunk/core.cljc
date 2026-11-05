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
     channel), :cancel (the caller's cancel channel, or nil) and :cancelled
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

(defn client [config]
  (let [host     (get config :hostname "localhost")
        port     (get config :port 5432)
        user     (get config :username "postgres")
        password (get config :password "")
        database (get config :database "postgres")
        debug    (get config :debug false)
        msocket  (ms/get-message-socket port host)
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
      (async/>! out {:type :StartupMessage :user user :database (:database config)})
      ;; scram holds the in-flight SCRAM-SHA-256 exchange between auth messages.
      (loop [scram nil]
        (let [message (async/<! in)]
          (when debug
            (println message))
          (case (:type message)
            nil
            (error "Connection closed during startup" {})

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

            (recur scram)))))))

(defn- row-decoder
  "From a RowDescription's fields, a function that reads a DataRow's view
   straight into a row map. Keys and per-column decoders are built once here,
   not once per row."
  [fields]
  (let [ks       (object-array (map (comp keyword :field-name) fields))
        decoders (object-array (map (comp oid-types/decoder :oid) fields))
        n        (count fields)]
    (fn [view]
      (binf/rr-i16 view) ;; column count, same as the RowDescription's
      (loop [i 0
             row (transient {})]
        (if (< i n)
          (let [len (binf/rr-i32 view)] ;; -1 is SQL NULL
            (recur (inc i)
                   (assoc! row (aget ks i)
                           (when (<= 0 len) ((aget decoders i) view len)))))
          (persistent! row))))))

(def ^:private default-fetch-size
  "Rows per batch for a query that has a :cancel channel."
  1000)

(defn- run-query
  "Sends one query and streams its rows to out until the server is ready
   again. Errors go to out as values; out is always closed. Once out is
   closed, it reads and discards the rest of the result.

   A query with params or a fetch size goes through the extended protocol.
   With a fetch size, each Execute asks for that many rows, and the next
   batch is asked for only while out is open, so closing out also stops the
   server. Otherwise sql goes as a simple Query, which can hold several
   statements."
  [{:keys [message-socket]} {:keys [sql params fetch-size out]}]
  (let [in       (:in message-socket)
        send     (:out message-socket)
        batched? (pos? fetch-size)]
    (async/go
      (async/>! send (if (or params batched?)
                       [{:type :Parse :query sql}
                        {:type :Bind :params params}
                        {:type :Describe}
                        {:type :Execute :max-rows fetch-size}
                        ;; Sync would end the implicit transaction, and the portal with it.
                        {:type (if batched? :Flush :Sync)}]
                       {:type :Query :query sql}))
      ;; live? turns false at the first put that out refuses because it's closed.
      ;; synced? turns true once Sync is sent; the server then ends with ReadyForQuery.
      (loop [decode-row nil
             live?      true
             synced?    (not batched?)]
        (let [message (async/<! in)]
          (case (:type message)
            nil            (do (when live?
                                 (async/>! out (error "Connection closed during query" {:sql sql})))
                               (async/close! out))
            :ReadyForQuery (async/close! out)
            :RowDescription (recur (row-decoder (:values message)) live? synced?)
            :DataRow       (recur decode-row (and live? (async/>! out (decode-row (:view message)))) synced?)
            :PortalSuspended (do (async/>! send (if live?
                                                  [{:type :Execute :max-rows fetch-size} {:type :Flush}]
                                                  [{:type :Close} {:type :Sync}]))
                                 (recur decode-row live? (not live?)))
            (:CommandComplete :EmptyQueryResponse)
            (do (when-not synced? (async/>! send {:type :Sync}))
                (recur decode-row live? true))
            ;; After an error the server skips to the next Sync.
            :ErrorMessage  (do (when-not synced? (async/>! send {:type :Sync}))
                               (recur decode-row (and live? (async/>! out (error (:message message) (assoc message :sql sql)))) true))
            (recur decode-row live? synced?)))))))

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
              (async/>! out result)
              (async/close! out)
              (recur)))
          (loop []
            (when-let [request (async/<! (:requests client))]
              (async/<! (run-query client request))
              (when-let [done (:done request)]
                (done))
              (recur))))))
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
   (let [out       (async/chan 64)
         cancelled (atom false)
         request   {:sql        (if (string? sql) sql (first sql))
                    :params     (when-not (string? sql) (mapv oid-types/param-text (rest sql)))
                    :fetch-size (or fetch-size (if cancel default-fetch-size 0))
                    :out        out
                    :cancel     cancel
                    :cancelled  cancelled}]
     (when cancel
       ;; A put already parked on out outlives close!, so take until out is
       ;; empty; run-query's next put is refused and it stops sending rows.
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
