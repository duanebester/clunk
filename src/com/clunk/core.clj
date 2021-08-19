(ns com.clunk.core
  (:import (java.lang Exception)
           (java.net Socket InetSocketAddress))
  (:require [org.clojars.smee.binary.core :as b]
            [clojure.core.async :as async]
            [clojure.core.match :as m]
            [clojure.tools.logging :as log]
            [com.clunk.pw :as pw]
            [com.clunk.sockets :as s]))

(def header-codec
  (b/ordered-map
   :tag :ubyte
   :len :int-be))

(def auth-tag-codec
  (b/ordered-map
   :tag :int-be))

(defn handle-close [in]
  (let [[close name] (b/decode [:byte (b/c-string "UTF8")] in)]
    {:type :Close :close close :name name}))

(defn handle-row-description [in]
  (let [cols (b/decode :short-be in)
        codec [(b/c-string "UTF8") :int-be :short-be :int-be :short-be :int-be :short-be]]
    (if (< 0 cols)
      (let [items (b/decode (b/repeated codec :length cols) in)]
        {:type :RowDescription :num-cols cols :values items})
      {:type :RowDescription :num-cols cols :values nil})))

(defn handle-data-row [in]
  (let [cols (b/decode :short-be in)]
    (loop [x 0 arr []]
      (if (<= cols x)
        {:type :DataRow :num-cols cols :values arr}
        (let [len (b/decode :int-be in)
              bs (if (<= 0 len)
                   (b/decode (b/repeated :byte :length len) in)
                   [])]
          (recur (inc x) (conj arr {:idx x :data (byte-array bs)})))))))

(defn handle-backend-key-data [in]
  (let [[id key] (b/decode [:int-be :int-be] in)]
    {:type :BackendKeyData :id id :key key}))

(defn handle-ready-for-query [in]
  (let [status (b/decode :byte in)]
    {:type :ReadyForQuery :status status}))

(defn handle-parameter-status [in]
  (let [status (b/decode (b/repeated (b/c-string "UTF8") :length 2) in)]
    {:type :ParameterStatus :status status}))

(defn handle-authentication [in]
  (let [tag (b/decode auth-tag-codec in)]
    (m/match tag
      {:tag 0}
      {:type :AuthenticationOk}
      {:tag 5}
      {:type :AuthenticationMD5 :salt (b/decode (b/repeated :byte :length 4) in)})))

(defn handle-error
  ([in] (handle-error in []))
  ([in errs]
   (let [bb (b/decode :byte in)]
     (if (< 0 (int bb))
       (let [msg (b/decode (b/c-string "UTF8") in)]
         (handle-error in (conj errs msg)))
       {:type :ErrorMessage :errors errs}))))

(defn handle-received [in]
  (try
    (let [header (b/decode header-codec in)]
      (m/match header
        {:tag 90}
        (handle-ready-for-query in)
        {:tag 84}
        (handle-row-description in)
        {:tag 83}
        (handle-parameter-status in)
        {:tag 82}
        (handle-authentication in)
        {:tag 75}
        (handle-backend-key-data in)
        {:tag 69}
        (handle-error in)
        {:tag 68}
        (handle-data-row in)
        {:tag 67}
        (handle-close in)
        :else (let [bs (b/decode (b/repeated :byte :length (- (:len header) 4)) in)]
                {:type :Unknown :payload bs})))
    (catch Exception e
      (log/error e)
      nil)))

(defn send-message [codec data outstream]
  (try
    (b/encode codec outstream data)
    true
    (catch Exception e
      (log/error "Error: " e)
      false)))


(defn get-startup [^String user ^String database]
  (let [len (+ (count user) (count "user") (count database) (count "database") 4 4 4 1)
        codec [:int-be :int-be (b/c-string "UTF8") (b/c-string "UTF8") (b/c-string "UTF8") (b/c-string "UTF8") :byte]
        data [(int len) (int 196608) "user" user "database" database (byte 0x00)]]
    [codec data]))

(defn get-query [^String query]
  (let [len (+ (count query) 1 4)
        codec [:byte :int-be (b/c-string "UTF8")]
        data [(byte 0x51) (int len) query]]
    [codec data]))

(defn get-password [^String user ^String password ^bytes salt]
  (let [md5-pw (pw/calculate-pw user password salt)
        len (+ (count md5-pw) 1 4)
        codec [:byte :int-be (b/c-string "UTF8")]
        data [(byte 0x70) (int len) md5-pw]]
    [codec data]))

(defn- init-async-socket [^Socket socket ^InetSocketAddress address]
  (let [in (.getInputStream socket)
        out (.getOutputStream socket)
        in-ch (async/chan)
        out-ch (async/chan)
        async-socket (s/map->AsyncSocket {:socket socket :address address :in in-ch :out out-ch})]

    (async/go-loop []
      (when (s/socket-open? socket)
        (let [msg (handle-received in)]
          (if-not msg
            (s/close-socket-client async-socket)
            (do
              (async/>! in-ch msg)
              (recur))))))

    (async/go-loop []
      (when (s/socket-open? socket)
        (let [[codec data] (async/<! out-ch)]
          (if-not (send-message codec data out)
            (s/close-socket-client async-socket)
            (recur)))))

    (log/info "New async socket opened on address" address)

    async-socket))

(defn socket-client
  "Given a port and an optional address (localhost by default), returns an AsyncSocket which must be explicitly
   started and stopped by the consumer."
  ([port]
   (socket-client (int port) (s/host-name (s/localhost))))
  ([^Integer port ^String address]
   (let [socket (Socket.)
         address (InetSocketAddress. address port)]

     (.connect socket address)
     (init-async-socket socket address))))

(defn close-client [client]
  (s/close-socket-client client))