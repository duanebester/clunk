(ns clunk.message-socket
  (:require [clunk.messages :as messages]
            [clunk.codecs :as codecs]
            [clunk.framing :as framing]
            [clunk.byte-buffer-socket :as bs]))

#?(:cljs (enable-console-print!))

(defrecord MessageSocket [buffer-socket paused? drain])

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
      78 (messages/handle-error bs :NoticeResponse)
      69 (messages/handle-error bs)
      65 (messages/handle-notification bs)
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
  "Connects; see byte-buffer-socket/get-buffer-socket. ssl is nil, or the TLS
   settings clunk.tls/connect takes."
  [port host ssl]
  (map->MessageSocket {:buffer-socket (bs/get-buffer-socket port host ssl)
                       :paused?       (volatile! false)
                       :drain         (volatile! nil)}))

(defn start!
  "Calls on-message with each backend message, in order, as it arrives,
   on-chunk-end after the messages of each socket read, and on-close once
   the connection ends. On the JVM they run on the connection's reader
   thread, which on-message may block to push back on the server. In JS,
   they run from the socket's events, and on-message pushes back by calling
   pause: no more messages are handled until resume."
  [{:keys [buffer-socket paused? drain]} on-message on-chunk-end on-close]
  #?(:clj  (let [rf ((framing/frames) (fn [_ view] (on-message (decode view)) nil))]
             (bs/start! buffer-socket (fn [chunk] (rf nil chunk) (on-chunk-end)) on-close))
     :cljs (let [views #js [] ;; framed, not yet handled from views[i]
                 i     (volatile! 0)
                 rf    ((framing/frames) (fn [_ view] (.push views view) nil))]
             (vreset! drain (fn []
                              (loop []
                                (when (and (not @paused?) (< @i (.-length views)))
                                  (let [view (aget views @i)]
                                    (aset views @i nil)
                                    (vswap! i inc)
                                    (on-message (decode view))
                                    (recur))))
                              (when-not @paused?
                                (set! (.-length views) 0)
                                (vreset! i 0)
                                (on-chunk-end))))
             (bs/start! buffer-socket
                        (fn [chunk]
                          (rf nil chunk)
                          ;; Messages still wait from the last read: stop reading
                          ;; until they're handled.
                          (if @paused? (bs/pause buffer-socket) (@drain)))
                        on-close))))

(defn send!
  "Writes a frontend message, or a vector of messages, in one write."
  [ms message]
  (bs/write! (:buffer-socket ms) (handle-frontend message)))

(defn pause
  "JS only: stops handling messages until resume. The socket stops reading
   too if another chunk arrives meanwhile."
  [{:keys [paused?]}]
  (vreset! paused? true))

(defn resume [{:keys [buffer-socket paused? drain]}]
  (vreset! paused? false)
  (@drain)
  (when-not @paused?
    (bs/resume buffer-socket)))

(defn close-message-socket [ms]
  (bs/close-buffer-socket (:buffer-socket ms)))

