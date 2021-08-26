(ns com.clunk.buffer-socket
  (:require 
   [clojure.core.async :as async]
   [clojure.tools.logging :as log])
  (:import
   (java.nio ByteBuffer)
   (java.nio.channels SocketChannel)
   (java.net InetSocketAddress)))

(defrecord ByteBufferSocket [^SocketChannel channel in-ch out-ch])

(defn- init-buffer-socket [channel]
  (let [in-ch (async/chan)
        out-ch (async/chan)
        header-buf (ByteBuffer/allocate 5)
        buff-sock (map->ByteBufferSocket {:channel channel :in in-ch :out out-ch})]

    (async/go-loop []
      (when (and (.isConnected channel) (.isOpen channel))
        (.clear header-buf)
        (let [n (.read channel header-buf)] ;; Read header
          (when (<= 5 n)
            (let [_ (.flip header-buf)
                  tag (.get header-buf)
                  len (.getInt header-buf)]
              #_(println (str "RECV Message - Tag: " tag " Length: " len))
              (when (pos? len)
                (let [bb (ByteBuffer/allocate (+ 1 len)) ;; Read rest of message
                      _ (.put bb tag)
                      _ (.putInt bb len)
                      _ (.read channel bb)]
                  (.clear header-buf)
                  (when (async/>! in-ch (.flip bb)) ;; Send to downstream listeners
                    (recur)))))))))

    (async/go-loop []
      (when (and (.isConnected channel) (.isOpen channel))
        (when-let [bs (async/<! out-ch)] ;; Receive buffer
          (try (.write channel bs) ;; Send it to SocketChannel
               (catch Exception e (log/error e)))
          (recur))))

    buff-sock))

(defn get-buffer-socket
  ([port]
   (get-buffer-socket (int port) "localhost"))
  ([^Integer port ^String address]
   (let [channel (SocketChannel/open)
         address (InetSocketAddress. address port)]
     (.configureBlocking channel true)
     (.connect channel address)
     (init-buffer-socket channel))))

(defn close-buffer-socket [{:keys [in out ^SocketChannel channel] :as this}]
  (when-not (.isOpen channel)
    (async/close! in)
    (async/close! out)
    (.shutdownInput channel)
    (.shutdownOutput channel)
    (.close channel)
    (assoc this :channel nil :in nil :out nil)))

;; Example usage:
(comment
  (defn print-ints
    "Prints byte array as ints"
    [ba]
    (println (map #(int %) ba)))

  ;; Connect socket to postgres
  (def buffer-socket (get-buffer-socket 5432))
  ;; startup message for user: "jimmy" and database: "world"
  (def startup-bytes (byte-array (map byte [0 0 0 35 0 3 0 0 117 115 101 114 0 106 105 109 109 121 0 100 97 116 97 98 97 115 101 0 119 111 114 108 100 0 0])))

  ;; Send ByteBuffer message
  (async/>!! (:out buffer-socket) (ByteBuffer/wrap startup-bytes))
  ;; Print received Message
  (print-ints (.array (async/<!! (:in buffer-socket))))
  (close-buffer-socket buffer-socket))