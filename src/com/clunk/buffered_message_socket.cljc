(ns com.clunk.buffered-message-socket
  (:require [clojure.core.match :as m]
            [clojure.core.async :as async]
            [com.clunk.message-socket :as ms]))

(defrecord BufferedMessageSocket [buffered-socket in-ch out-ch])

(def status-map (atom {}))

(defn handle-backend-message [message]
  (println "Backend: " message)
  (m/match message
    {:type :ParameterStatus}
    (let [[key val] (:status message)]
      (swap! status-map assoc (key key) val))
    (else nil)))

(defn handle-incoming [incoming]
  (println "Incoming: " incoming))

(defn get-buffered-socket
  ([port] (get-buffered-socket port "localhost"))
  ([port host]
   (let [buffered-socket (ms/get-message-socket port host)
         in-ch         (async/chan)
         out-ch        (async/chan)
         message-sock  (map->BufferedMessageSocket {:buffered-socket buffered-socket
                                                    :in            in-ch
                                                    :out           out-ch})]

     (async/go-loop []
       (let [ms (async/<! (:in buffered-socket))]
         (if-not ms
           (ms/close-message-socket buffered-socket)
           (when (async/>! in-ch (handle-backend-message ms))
             (recur)))))

     (async/go-loop []
       (let [incoming (async/<! out-ch)]
         (if-not incoming
           (ms/close-message-socket buffered-socket)
           (when (async/>! (:out buffered-socket) (handle-incoming incoming))
             (recur)))))

     message-sock)))

