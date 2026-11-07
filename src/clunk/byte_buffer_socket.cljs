(ns clunk.byte-buffer-socket
  (:require [clunk.log :as log]
            [clunk.tls :as tls]))

(defn connected?
  "True until the socket is destroyed, as it is once the server hangs up.
   Writes made while it is still connecting are buffered."
  [^js client]
  (not (.-destroyed client)))

;; client is a net.Socket, or with TLS a Duplex that acts like one (see clunk.tls).
(defrecord ByteBufferSocket [client])

(defn- chunk->buffer
  "Copies a Node Buffer's bytes into their own ArrayBuffer. A Buffer can be a
   window into a larger shared pool, so its byteOffset matters."
  [^js d]
  (.slice (.-buffer d) (.-byteOffset d) (+ (.-byteOffset d) (.-length d))))

(defn get-buffer-socket
  "Starts connecting and returns at once; writes wait until the connection is
   ready. ssl is nil, or the TLS settings clunk.tls/connect takes."
  [^Integer port ^String address ssl]
  (let [client (tls/connect port address ssl)]
    ;; An unhandled "error" (refused, reset) would crash the process; the
    ;; "close" that follows it ends the connection instead.
    (.on client "error" (fn [_]))
    (->ByteBufferSocket client)))

(defn start!
  "Calls on-chunk with each chunk read, as an ArrayBuffer, then on-close once
   the connection ends."
  [{:keys [^js client]} on-chunk on-close]
  (.on client "data" (fn [d]
                       (try (on-chunk (chunk->buffer d))
                            (catch :default e
                              (log/error "Closing the connection after an error reading it" e)
                              (.destroy client)))))
  (.on client "close" (fn [_] (on-close))))

(defn write!
  "Writes a binf view (a DataView); Node buffers what the socket can't take yet."
  [{:keys [^js client]} bs]
  (when (connected? client)
    (.write client (js/Uint8Array. (.-buffer bs)))))

(defn pause
  "Stops reading until resume, so a slow reader pushes back on the server."
  [{:keys [^js client]}]
  (.pause client))

(defn resume [{:keys [^js client]}]
  (when (.isPaused client)
    (.resume client)))

(defn close-buffer-socket [{:keys [^js client]}]
  (.end client))
