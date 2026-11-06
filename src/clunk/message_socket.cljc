(ns clunk.message-socket
  (:require [clojure.core.async :as async]
            [clunk.pw :as pw]
            [clunk.messages :as messages]
            [clunk.codecs :as codecs]
            [clunk.framing :as framing]
            [clunk.byte-buffer-socket :as bs]))

#?(:cljs (enable-console-print!))

(defrecord MessageSocket [buffer-socket in-ch out-ch])

(defn connected?
  [^MessageSocket ms]
  (bs/connected? (:client (:buffer-socket ms))))

(defn closed-by
  "The error that closed the connection, if one did and the platform keeps
   it: Node's socket does, as errored. The JVM fails while connecting instead."
  [^MessageSocket ms]
  #?(:clj  nil
     :cljs (when-let [^js e (some-> ^js (:client (:buffer-socket ms)) .-errored)]
             ;; Refused on both IPv6 and IPv4, it's an AggregateError with no message.
             (if (and (empty? (.-message e)) (.-errors e)) (last (.-errors e)) e))))

(defn handle-backend [bs]
  (let [header (codecs/rr-header bs)]
    (case (int (:tag header))
      90 (messages/handle-ready-for-query bs)
      84 (messages/handle-row-description bs)
      83 (messages/handle-parameter-status bs)
      82 (messages/handle-authentication bs)
      75 (messages/handle-backend-key-data bs)
      69 (messages/handle-error bs)
      68 (messages/handle-data-row bs)
      67 (messages/handle-command-complete bs)
      ;; Messages with no body worth reading.
      49  {:type :ParseComplete}
      50  {:type :BindComplete}
      51  {:type :CloseComplete}
      73  {:type :EmptyQueryResponse}
      110 {:type :NoData}
      115 {:type :PortalSuspended}
      (messages/handle-unknown bs header))))

(defn handle-frontend
  "Builds the bytes for a message, or for a vector of messages sent together."
  [message]
  (if (vector? message)
    (messages/build-batch (map handle-frontend message))
    (case (:type message)
      :StartupMessage      (messages/build-startup (:user message) (:database message))
      :Query               (messages/build-query (:query message))
      :PasswordMessage     (messages/build-password (:password message))
      :SASLInitialResponse (messages/build-sasl-initial-response (:mechanism message) (:data message))
      :SASLResponse        (messages/build-sasl-response (:data message))
      :Parse               (messages/build-parse (:query message))
      :Bind                (messages/build-bind (:params message))
      :Describe            (messages/build-describe-portal)
      :Execute             (messages/build-execute (:max-rows message))
      :Close               (messages/build-close-portal)
      :Flush               (messages/build-flush)
      :Sync                (messages/build-sync))))

(defn- decode
  "handle-backend, except that a message that fails to decode becomes an error
   message instead of an exception, so the bytes after it still frame."
  [view]
  (try (handle-backend view)
       (catch #?(:clj Exception :cljs :default) e
         {:type :ErrorMessage :message (str "Could not decode message: " (ex-message e))})))

(defn get-message-socket
  "Every translation happens in the channels' transducers, as bytes are put on
   :in by the socket and as messages are put on :out by the caller, so nothing
   sits between the socket and its reader."
  ([port] (get-message-socket port "localhost"))
  ([port host] (get-message-socket port host nil))
  ([port host ssl]
   (let [in  (async/chan 64 (comp (framing/frames) (map decode)))
         out (async/chan 64 (map handle-frontend))]
     (map->MessageSocket {:buffer-socket (bs/get-buffer-socket port host in out ssl)
                          :in            in
                          :out           out}))))

(defn close-message-socket [{:keys [in out buffer-socket]
                             :as   this}]
  (bs/close-buffer-socket buffer-socket)
  (async/close! in)
  (async/close! out)
  (assoc this :buffer-socket nil :in nil :out nil))

;; Example usage
(comment
  ;; clj -M -m cljs.main --target node --output-to ms.js -c clunk.message-socket && node ms.js
  (def username "jimmy")
  (def password "banana")
  (def database "world")

  (def message-socket (get-message-socket 5432))

  (async/go-loop []
    (when-let [message (async/<! (:in message-socket))]
      (println message)
      (case (:type message)
        :AuthenticationMD5
        ;; Respond with password
        (let [salt     (:salt message)
              password (pw/calculate-pw username password salt)]
          (async/>! (:out message-socket) {:type     :PasswordMessage
                                           :password password}))
        nil)
      (recur)))

  (async/go
    (async/>! (:out message-socket) {:type     :StartupMessage
                                     :user     username
                                     :database database}))

  (async/go
    (async/>! (:out message-socket) {:type     :Query
                                     :query    "SELECT name FROM country LIMIT 3;"}))


  (close-message-socket message-socket))