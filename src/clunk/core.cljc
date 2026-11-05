(ns clunk.core
  (:require [clojure.core.async :as async]
            [clunk.pw :as pw]
            [clunk.oid-types :as oid-types]
            [clunk.vendor.binf :as binf]
            [clunk.message-socket :as ms])
  #?(:clj (:import (java.util.concurrent CompletableFuture)))
  #?(:cljs (:require-macros [clunk.core])))

;; A Client owns one connection. Requests queue on :requests, and a single
;; session loop (started by connect) is the only reader of the socket, so
;; queries run one at a time in the order they were made.
(defrecord Client [message-socket config requests])

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

(defn- run-query
  "Sends one simple query and streams its rows to out until the server is
   ready again. Errors go to out as values; out is always closed. Once out is
   closed, it reads and discards the rest of the result."
  [{:keys [message-socket]} {:keys [sql out]}]
  (let [in (:in message-socket)]
    (async/go
      (async/>! (:out message-socket) {:type :Query :query sql})
      ;; live? turns false at the first put that out refuses because it's closed.
      (loop [decode-row nil
             live?      true]
        (let [message (async/<! in)]
          (case (:type message)
            nil            (do (when live?
                                 (async/>! out (error "Connection closed during query" {:sql sql})))
                               (async/close! out))
            :ReadyForQuery (async/close! out)
            :RowDescription (recur (row-decoder (:values message)) live?)
            :DataRow       (recur decode-row (and live? (async/>! out (decode-row (:view message)))))
            :ErrorMessage  (recur decode-row (and live? (async/>! out (error (:message message) (assoc message :sql sql)))))
            (recur decode-row live?)))))))

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
              (recur))))))
    ready))

(defn query
  "Queues sql and returns a channel of row maps, keyed by column name, that
   closes after the last row. A failed query puts an error on the channel.

   To stop early, pass a :cancel channel, such as a promise-chan or a timeout,
   and close it or put on it: the returned channel closes and the client
   discards the rest of the result. Closing the returned channel alone can
   leave the client waiting to put a row, holding up every later query."
  ([client sql] (query client sql nil))
  ([^Client client sql {:keys [cancel]}]
   (let [out (async/chan 64)]
     (if (async/put! (:requests client) {:sql sql :out out})
       (when cancel
         ;; A put already parked on out outlives close!, so take until out is
         ;; empty; run-query's next put is refused and it stops sending rows.
         (async/go
           (async/<! cancel)
           (async/close! out)
           (loop [] (when (async/<! out) (recur)))))
       (async/close! out))
     out)))

(defn execute
  "Runs sql and returns all its rows as a vector of maps: a CompletableFuture
   on the JVM (deref it with @) and a js/Promise in ClojureScript. A failed
   query fails it with the error."
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
      reducing thread, so don't reduce it inside a go block."
     [client sql]
     (reify clojure.lang.IReduceInit
       (reduce [_ f init]
         (let [cancel (async/promise-chan)
               ch     (query client sql {:cancel cancel})]
           (try
             (loop [acc init]
               (let [row (async/<!! ch)]
                 (cond
                   (nil? row)   acc
                   (error? row) (throw row)
                   :else        (let [acc (f acc row)]
                                  (if (reduced? acc) @acc (recur acc))))))
             (finally
               (async/close! cancel))))))))

(defmacro <query!
  "Inside a go block, parks until the query finishes and returns a vector of
   all its rows (and any error)."
  [client sql]
  (if (:ns &env)
    `(cljs.core.async/<! (cljs.core.async/into [] (query ~client ~sql)))
    `(clojure.core.async/<! (clojure.core.async/into [] (query ~client ~sql)))))

(defn close [{:keys [message-socket requests] :as this}]
  (async/close! requests)
  (ms/close-message-socket message-socket)
  (assoc this :message-socket nil :requests nil))
