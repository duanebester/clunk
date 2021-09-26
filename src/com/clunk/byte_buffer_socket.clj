(ns com.clunk.byte-buffer-socket
  (:require
   [clojure.core.async :as async]
   [clojure.tools.logging :as log])
  (:import
   (java.nio ByteBuffer)
   (java.nio.channels SocketChannel)
   (java.net InetSocketAddress)))

(defrecord ByteBufferSocket [^SocketChannel client in-ch out-ch])

(defn connected? [^SocketChannel client]
  (or (.isConnected client) (.isOpen client)))

(defn- parse-header [bb]
  (let [_ (.flip bb)
        tag (.get bb)
        len (.getInt bb)]
    (log/info (str "RECEIVED TAG: " tag ", LENGTH: " len))
    [tag len]))

(defn- read-message [tag len client]
  (let [bb (ByteBuffer/allocate (+ 1 len)) ;; Allocate for full message
        _  (.put bb tag) ;; Add back tag
        _  (.putInt bb len) ;; Add back length
        _  (.read client bb)] ;; Read rest of message
    (.flip bb)))

(defn- init-buffer-socket [client]
  (let [in-ch      (async/chan)
        out-ch     (async/chan)
        header-buf (ByteBuffer/allocate 5)
        buff-sock  (map->ByteBufferSocket {:client client
                                           :in      in-ch
                                           :out     out-ch})]
    (async/go-loop []
      (when (connected? client)
        (let [n (.read client header-buf)] ;; Read header
          (when (<= 5 n)
            (let [[tag len] (parse-header header-buf)]
              (when (pos? len)
                (let [bb (read-message tag len client)]
                  (.clear header-buf)
                  (when (async/>! in-ch bb) ;; Send to downstream listeners
                    (recur)))))))))

    (async/go-loop []
      (when (connected? client)
        (when-let [bs (async/<! out-ch)] ;; Receive buffer
          (try (.write client bs) ;; Send it to SocketChannel
               (catch Exception e (log/error e)))
          (recur))))

    buff-sock))

(defn get-buffer-socket
  [^Integer port ^String address]
  (let [client (SocketChannel/open)
        address (InetSocketAddress. address port)]
    (.configureBlocking client true)
    (.connect client address)
    (init-buffer-socket client)))

(defn close-buffer-socket [{:keys [in out ^SocketChannel client]
                            :as   this}]
  (when (connected? client)
    (async/close! in)
    (async/close! out)
    (.shutdownInput client)
    (.shutdownOutput client)
    (.close client)
    (assoc this :client nil :in nil :out nil)))

;; Example usage
(comment
  ;; Utility fn
  (defn print-ints
    "Prints byte array as ints"
    [ba]
    (println (map #(int %) ba)))

  (def buffer-socket (get-buffer-socket 5432 "localhost"))

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