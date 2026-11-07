(ns clunk.byte-buffer-socket
  (:require [clunk.log :as log]
            [clunk.tls :as tls])
  (:import
   (java.nio ByteBuffer)
   (java.nio.channels ByteChannel)
   (java.util Arrays)))

;; client is a connected SocketChannel, or a TLS connection behind the same
;; ByteChannel interface (see clunk.tls).
(defrecord ByteBufferSocket [^ByteChannel client ^String reader-name])

(defn connected? [^ByteChannel client]
  (.isOpen client))

(defn get-buffer-socket
  "Connects, blocking until the connection, and its TLS handshake if ssl asks
   for one, is ready. ssl is nil, or the TLS settings clunk.tls/connect
   takes. Throws if either fails."
  [^Integer port ^String address ssl]
  (->ByteBufferSocket (tls/connect port address ssl) (str "clunk-reader-" address ":" port)))

(defn start!
  "Calls on-chunk with each chunk read, as a byte array, then on-close once,
   when the connection ends. Both run on the connection's own thread, named
   clunk-reader-<host>:<port>, which reads for as long as the connection is
   open, idle or not, so it sees messages the server sends unasked and notices
   a server that hangs up. on-chunk may block it, which pushes back on the
   server.

   It's a daemon platform thread, not a virtual one: on a 24-thread machine a
   platform reader handled up to half again as many small queries, at less
   than half the CPU each, since waking a virtual thread blocked on a socket
   also wakes the JDK's poller threads. A daemon, so a client nobody closed
   doesn't keep the JVM running."
  [{:keys [^ByteChannel client ^String reader-name]} on-chunk on-close]
  (let [^Runnable read-loop
        (bound-fn []
          (let [bb (ByteBuffer/allocate 65536)]
            (try
              (loop []
                (let [n (try (.read client bb)
                             (catch java.io.IOException _ -1))] ;; Socket closed under us
                  (when-not (neg? n)
                    (let [chunk (Arrays/copyOf (.array bb) (int n))]
                      (.clear bb)
                      (on-chunk chunk)
                      (recur)))))
              (catch Throwable t
                (log/error "Closing the connection after an error reading it" t))
              (finally
                ;; Closed first, so connected? is false by the time on-close runs.
                (try (.close client) (catch java.io.IOException _))
                (try (on-close)
                     (catch Throwable t
                       (log/error "Error ending the connection's queries" t)))))))]
    (doto (Thread. read-loop reader-name)
      (.setDaemon true)
      (.start)))
  nil)

(defn write!
  "Writes a ByteBuffer whose position is at the end of its bytes, on the
   caller's thread. SocketChannel and SSLSocket each keep concurrent writes
   whole. clunk writes a query only once the server has answered the last
   one, so the socket's send buffer is empty and only a request larger than
   it waits for the server to read."
  [{:keys [^ByteChannel client]} ^ByteBuffer bs]
  (.flip bs)
  (try (while (.hasRemaining bs)
         (.write client bs))
       ;; A connection the server closed fails its write; the read loop
       ;; already reports that to the query, so it's only debug.
       (catch java.io.IOException e (log/debug "Write to a closed connection" e))))

;; The reader thread blocks to push back, so there's nothing to pause.
(defn pause [_])
(defn resume [_])

(defn close-buffer-socket
  "Idempotent."
  [{:keys [^ByteChannel client]}]
  (.close client))
