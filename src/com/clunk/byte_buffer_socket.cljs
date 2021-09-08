(ns com.clunk.byte-buffer-socket
  (:require
   [cljs.core.async :as async]
   [cljs.nodejs :as node]))

(def net (node/require "net"))

(defrecord ByteBufferSocket [client in-ch out-ch])

(defn- init-buffer-socket [client]
  (let [in-ch      (async/chan)
        out-ch     (async/chan)
        buff-sock  (map->ByteBufferSocket {:client  client
                                           :in      in-ch
                                           :out     out-ch})]

    (.on client "data" (fn [d]
                         (let [b (.-buffer d)
                               buf (js/DataView. b)]
                           (async/put! in-ch buf))))

    (async/go-loop []
      (when-let [bs (async/<! out-ch)] ;; Receive buffer
        (.write client (js/Uint8Array. (.-buffer bs))) ;; Send it to SocketChannel
        (recur)))

    buff-sock))

(defn get-buffer-socket
  ([port]
   (get-buffer-socket (int port) "localhost"))
  ([^Integer port ^String address]
   (let [client (.createConnection net port address)]
     (init-buffer-socket client))))

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
  (def buffer-socket (get-buffer-socket 5432))
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