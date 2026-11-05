(ns clunk.byte-buffer-socket
  (:require
   [clojure.core.async :as async]
   [clojure.tools.logging :as log])
  (:import
   (java.nio ByteBuffer)
   (java.nio.channels SocketChannel)
   (java.net InetSocketAddress)
   (java.util Arrays)))

(defrecord ByteBufferSocket [^SocketChannel client in-ch out-ch])

(defn connected? [^SocketChannel client]
  (and (.isConnected client) (.isOpen client)))

(defn- init-buffer-socket [^SocketChannel client in-ch out-ch]
  (let [buff-sock (map->ByteBufferSocket {:client client
                                          :in     in-ch
                                          :out    out-ch})]
    ;; Reads block, so they run on a real thread rather than a go block.
    (async/thread
      (let [bb (ByteBuffer/allocate 65536)]
        (loop []
          (let [n (try (.read client bb)
                       (catch java.io.IOException _ -1))] ;; Socket closed under us
            (if (neg? n)
              (async/close! in-ch)
              (let [chunk (Arrays/copyOf (.array bb) (int n))]
                (.clear bb)
                (when (async/>!! in-ch chunk) ;; Blocks while readers are behind
                  (recur))))))))

    (async/thread
      (loop []
        (when-let [^ByteBuffer bs (async/<!! out-ch)] ;; nil once out-ch is closed
          (.flip bs)
          (try (while (.hasRemaining bs)
                 (.write client bs)) ;; Send it to SocketChannel
               (catch Exception e (log/error e)))
          (recur))))

    buff-sock))

(defn get-buffer-socket
  "Puts each chunk read as a byte array on in-ch, and writes each ByteBuffer
   taken from out-ch. Transducers on those channels do the rest."
  [^Integer port ^String address in-ch out-ch]
  (let [client (SocketChannel/open)
        address (InetSocketAddress. address port)]
    (.connect client address)
    (init-buffer-socket client in-ch out-ch)))

(defn close-buffer-socket
  "Idempotent: both the caller and the message socket's read loop close it."
  [{:keys [in out ^SocketChannel client]
    :as   this}]
  (async/close! in)
  (async/close! out)
  (.close client)
  (assoc this :client nil :in nil :out nil))

;; Example usage
(comment
  ;; Utility fn
  (defn print-ints
    "Prints byte array as ints"
    [ba]
    (println (map #(int %) ba)))

  (def buffer-socket (get-buffer-socket 5432 "localhost" (async/chan) (async/chan)))

  ;; Startup message for user: "jimmy", and database: "world"
  (def startup-bytes
    (->>
     (map byte [0 0 0 35 0 3 0 0 117 115 101 114 0 106 105 109 109 121 0 100 97 116 97 98 97 115 101 0 119 111 114 108 100 0 0])
     byte-array
     ByteBuffer/wrap))

  ;; Send ByteBuffer message
  (async/>!! (:out buffer-socket) startup-bytes)

  ;; Print received Message
  (print-ints (.array (async/<!! (:in buffer-socket))))

  ;; Close
  (close-buffer-socket buffer-socket))