(ns clunk.byte-buffer-socket
  (:require
   [cljs.core.async :as async]
   ["net" :as net]))

(defn connected? [^js client]
  (not (and (.-connecting client) (.-destroyed client))))

(defrecord ByteBufferSocket [client in-ch out-ch])

(defn- chunk->buffer
  "Copies a Node Buffer's bytes into their own ArrayBuffer. A Buffer can be a
   window into a larger shared pool, so its byteOffset matters."
  [^js d]
  (.slice (.-buffer d) (.-byteOffset d) (+ (.-byteOffset d) (.-length d))))

(defn- init-buffer-socket [^js client in-ch out-ch]
  (let [buff-sock  (map->ByteBufferSocket {:client  client
                                           :in      in-ch
                                           :out     out-ch})]

    ;; Pause reading until the chunk is accepted, so a slow reader pushes back on the server.
    (.on client "data" (fn [d]
                         (.pause client)
                         (async/put! in-ch (chunk->buffer d) (fn [_] (.resume client)))))
    (.on client "close" (fn [_] (async/close! in-ch))) ;; Tell readers the server hung up

    (async/go-loop []
      (when (connected? (:client buff-sock))
        (when-let [bs (async/<! out-ch)] ;; nil once out-ch is closed
          (.write client (js/Uint8Array. (.-buffer bs))) ;; Send it to the socket
          (recur))))

    buff-sock))

(defn get-buffer-socket
  "Puts each chunk read as an ArrayBuffer on in-ch, and writes each view taken
   from out-ch. Transducers on those channels do the rest."
  [^Integer port ^String address in-ch out-ch]
  (let [client (.createConnection net port address)]
    (init-buffer-socket client in-ch out-ch)))

(defn close-buffer-socket [{:keys [in out client]
                            :as   this}]
  (when true
    (async/close! in)
    (async/close! out)
    (.end client)
    (assoc this :client nil :in nil :out nil)))

;; Example usage:
(comment
  ;; Connect socket to postgres
  (def buffer-socket (get-buffer-socket 5432 "localhost" (async/chan) (async/chan)))
  ;; startup message for user: "jimmy" and database: "world"
  (def startup-bytes (to-array [0 0 0 35 0 3 0 0 117 115 101 114 0 106 105 109 109 121 0 100 97 116 97 98 97 115 101 0 119 111 114 108 100 0 0]))

  (def buff (js/DataView. (js/ArrayBuffer. (count startup-bytes))))
  (doseq [i (range (count startup-bytes))]
    (.setUint8 buff i (aget startup-bytes i)))

  ;; Send byte buffer message
  (async/go
    (async/>! (:out buffer-socket) buff)
  ;; Print received Message
    (println (async/<! (:in buffer-socket)))
    (close-buffer-socket buffer-socket)))