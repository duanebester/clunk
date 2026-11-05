(ns clunk.core
  (:require [clojure.core.async :as async]
            [clunk.pw :as pw]
            [clunk.oid-types :refer [oid-converter]]
            [clunk.message-socket :as ms])
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

(defn- row->map [fields row]
  (into {} (for [rv (:values row)]
             (let [field (nth fields (:idx rv))]
               [(keyword (:field-name field))
                (when-some [data (:data rv)] ;; nil is SQL NULL
                  (oid-converter (:oid field) data (:length rv)))]))))

(defn- run-query
  "Sends one simple query and streams its rows to out until the server is
   ready again. Errors go to out as values; out is always closed."
  [{:keys [message-socket]} {:keys [sql out]}]
  (let [in (:in message-socket)]
    (async/go
      (async/>! (:out message-socket) {:type :Query :query sql})
      (loop [fields nil]
        (let [message (async/<! in)]
          (case (:type message)
            nil            (do (async/>! out (error "Connection closed during query" {:sql sql}))
                               (async/close! out))
            :ReadyForQuery (async/close! out)
            :RowDescription (recur (:values message))
            :DataRow       (do (async/>! out (row->map fields message))
                               (recur fields))
            :ErrorMessage  (do (async/>! out (error (:message message) (assoc message :sql sql)))
                               (recur fields))
            (recur fields)))))))

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
   closes after the last row. A failed query puts an error on the channel."
  [^Client client sql]
  (let [out (async/chan 64)]
    (when-not (async/put! (:requests client) {:sql sql :out out})
      (async/close! out))
    out))

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
