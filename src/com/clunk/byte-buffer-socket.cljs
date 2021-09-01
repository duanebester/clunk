(ns com.clunk.byte-buffer-socket
  (:require
   [cljs.nodejs :as node]
   [octet.core :as buf]
   [cljs.core.async :as async]))

(def net (node/require "net"))

(defrecord ByteBufferSocket [client in-ch out-ch])

;; (defn- init-buffer-socket [client]
;;   (let [in-ch      (async/chan)
;;         out-ch     (async/chan)
;;         header-buf (buf/allocate 5)
;;         buff-sock  (map->ByteBufferSocket {:client  client
;;                                            :in      in-ch
;;                                            :out     out-ch})]

;;     (async/go-loop []
;;       (when (and (.isConnected channel) (.isOpen client))
;;         (.clear header-buf)
;;         (let [n (.read channel header-buf)] ;; Read header
;;           (when (<= 5 n)
;;             (let [_   (.flip header-buf)
;;                   tag (.get header-buf)
;;                   len (.getInt header-buf)]
;;               #_(println (str "RECV Message - Tag: " tag " Length: " len))
;;               (when (pos? len)
;;                 (let [bb (buf/allocate (+ 1 len)) ;; Read rest of message
;;                       _  (.put bb tag)
;;                       _  (.putInt bb len)
;;                       _  (.read channel bb)]
;;                   (.clear header-buf)
;;                   (when (async/>! in-ch (.flip bb)) ;; Send to downstream listeners
;;                     (recur)))))))))

;;     (async/go-loop []
;;       (when (and (.isConnected channel) (.isOpen channel))
;;         (when-let [bs (async/<! out-ch)] ;; Receive buffer
;;           (try (.write channel bs) ;; Send it to SocketChannel
;;                (catch Exception e (log/error e)))
;;           (recur))))

;;     buff-sock))

;; (defn send-data []
;;   (let [version 3
;;         packet-type 0
;;         content-type 1
;;         fname "timestamp"
;;         data "[]"
;;         size (+ 1 1 1 2 (count fname) 4 (count data))
;;         buffer (js/Buffer. size)]

;;     (.writeInt8 buffer version 0)
;;     (.writeInt8 buffer packet-type 1)
;;     (.writeInt8 buffer content-type 2)
;;     (.writeUInt16BE buffer (count fname) 3)
;;     (.write buffer fname 5)
;;     (.writeUInt32BE buffer (count data) (+ 5 (count fname)))
;;     (.write buffer data (+ 9 (count fname)))

;;     (.write client buffer)))

;; (defn recv-data [d]
;;   (let [buffer (js/Buffer. d)]
;;     (println (.toString buffer "UTF8" 8)))
;;   (.end client))


;; (defn main [& _]
;;   (def client (.createConnection net 2104))
;;   (.on client "connect" send-data)
;;   (.on client "data" recv-data))