(ns com.clunk.sockets
  (:require [clojure.core.async :as async])
  (:import (java.net Socket InetAddress InetSocketAddress)))

(defn socket-open? [^Socket socket]
  (not (or (.isClosed socket) (.isInputShutdown socket) (.isOutputShutdown socket))))

(defn ^InetAddress localhost []
  (InetAddress/getLocalHost))

(defn host-name [^InetAddress address]
  (.getHostName address))

(defrecord AsyncSocket [^Socket socket ^InetSocketAddress address in-ch out-ch])

(defn close-socket-client [{:keys [in out ^Socket socket] :as this}]
  (when-not (.isInputShutdown socket)  (.shutdownInput socket))
  (when-not (.isOutputShutdown socket) (.shutdownOutput socket))
  (when-not (.isClosed socket)         (.close socket))
  (async/close! in)
  (async/close! out)
  (assoc this :socket nil :in nil :out nil))

