(ns com.clunk.message-socket
  (:require [clojure.core.match :as m]
            [clojure.core.async :as async]
            [octet.core :as buf]
            [com.clunk.messages :as messages]
            [com.clunk.pw :as pw]
            [com.clunk.codecs :as codecs]
            [com.clunk.buffer-socket :as bs]))

(defrecord MessageSocket [buffer-socket in-ch out-ch])

(defn handle-backend-key-data [bs]
  (let [[id key] (buf/read bs (buf/spec buf/int32 buf/int32))]
    {:type :BackendKeyData :id id :key key}))

(defn handle-ready-for-query [bs]
  (let [status (buf/read bs (buf/spec buf/byte))]
    {:type :ReadyForQuery :status status}))

(defn handle-parameter-status [bs]
  (let [status (buf/read bs (buf/spec codecs/cstring codecs/cstring))]
    {:type :ParameterStatus :status status}))

(defn handle-data-row [bs]
  (let [[r cols] (buf/read* bs buf/int16)]
    (loop [i 0 arr [] offset r]
      (if (<= cols i)
        {:type :DataRow :num-cols cols :values arr}
        (let [[r1 len] (buf/read* bs buf/int32 {:offset offset})
              offset (+ r1 offset)
              [r2 data] (buf/read* bs (buf/repeat len  buf/byte) {:offset offset})
              offset (+ r2 offset)]
          (recur (inc i) (conj arr {:idx i :data (byte-array data)}) offset))))))

(defn handle-row-description [bs]
  (let [cols (buf/read bs buf/int16)
        _ (.position bs (+ (buf/size buf/int16) (.position bs)))
        bbs (.slice bs)
        codec codecs/row-desc-codec]
    (if (< 0 cols)
      (let [items (buf/read bbs (buf/repeat cols codec))]
        {:type :RowDescription :num-cols cols :values items})
      {:type :RowDescription :num-cols cols :values nil})))

(defn handle-authentication [bs]
  (let [auth-req (buf/read bs codecs/auth-req-codec)
        _ (.position bs (+ (buf/size codecs/auth-req-codec) (.position bs)))
        bbs (.slice bs)]
    (m/match auth-req
      {:tag 0}
      {:type :AuthenticationOk}
      {:tag 5}
      {:type :AuthenticationMD5 :salt (buf/read bbs (buf/repeat 4 buf/byte))})))

(defn handle-error
  ([bs] (handle-error bs []))
  ([bs errs]
   (let [head (buf/read bs buf/byte)
         _ (.position bs (+ (buf/size buf/byte) (.position bs)))
         tail (.slice bs)]
     (if (< 0 head)
       (let [msg (buf/read tail codecs/cstring)
             _ (.position tail (+ 1 (count msg) (.position tail)))]
         (handle-error (.slice tail) (conj errs msg)))
       {:type :ErrorMessage :errors errs}))))

(defn handle-backend [bs]
  (let [header (buf/read bs codecs/header-codec)
        _ (.position bs (+ (buf/size codecs/header-codec) (.position bs)))
        bbs (.slice bs)]
    (m/match header
      {:tag 90}
      (handle-ready-for-query bbs)
      {:tag 84}
      (handle-row-description bbs)
      {:tag 83}
      (handle-parameter-status bbs)
      {:tag 82}
      (handle-authentication bbs)
      {:tag 75}
      (handle-backend-key-data bbs)
      {:tag 69}
      (handle-error bbs)
      {:tag 68}
      (handle-data-row bbs)
      :else (let [unk (buf/read bbs (buf/repeat (- (:len header) 4) buf/byte))]
              {:type :Unknown :header header :payload unk}))))

(defn handle-frontend [message]
  (m/match message
    {:type :StartupMessage}
    (messages/build-startup (:user message) (:database message))
    {:type :Query}
    (messages/build-query (:query message))
    {:type :PasswordMessage}
    (messages/build-password (:password message))))

(defn get-message-socket
  ([port] (get-message-socket port "localhost"))
  ([port host]
   (let [buffer-socket (bs/get-buffer-socket port host)
         in-ch (async/chan)
         out-ch (async/chan)
         message-sock (map->MessageSocket {:buffer-socket buffer-socket
                                           :in in-ch
                                           :out out-ch})]

     (async/go-loop []
       (when (and (.isConnected (:channel buffer-socket)) (.isOpen (:channel buffer-socket)))
         (let [bbs (async/<! (:in buffer-socket))]
           (if-not bbs
             (bs/close-buffer-socket buffer-socket)
             (when (async/>! in-ch (handle-backend bbs))
               (recur))))))

     (async/go-loop []
       (when (and (.isConnected (:channel buffer-socket)) (.isOpen (:channel buffer-socket)))
         (let [message (async/<! out-ch)]
           (if-not message
             (bs/close-buffer-socket buffer-socket)
             (when (async/>! (:out buffer-socket) (handle-frontend message))
               (recur))))))

     message-sock)))

(defn close-message-socket [{:keys [in out buffer-socket] :as this}]
  (bs/close-buffer-socket buffer-socket)
  (async/close! in)
  (async/close! out)
  (assoc this :buffer-socket nil :in nil :out nil))


;; Example usage:
(comment
  (def username "jimmy")
  (def password "banana")
  (def database "world")
  (def message-socket (get-message-socket 5432))

  (async/>!! (:out message-socket) {:type :StartupMessage :user username :database database})

  (async/go-loop []
    (when-let [message (async/<! (:in message-socket))]
      (println message)
      (m/match message
        {:type :AuthenticationOk}
        ()
        {:type :AuthenticationMD5}
          ;; Respond with password
        (let [salt (byte-array (:salt message))
              password (pw/calculate-pw username password salt)]
          (async/>! (:out message-socket) {:type :PasswordMessage :password password}))
        :else ())
      (recur)))

  (close-message-socket message-socket))