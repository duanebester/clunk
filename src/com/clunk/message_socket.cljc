(ns com.clunk.message-socket
  (:require [clojure.core.match :as m]
            [clojure.core.async :as async]
            [octet.core :as buf]
            [com.clunk.pw :as pw]
            [com.clunk.messages :as messages]
            [com.clunk.codecs :as codecs]
            [com.clunk.byte-buffer-socket :as bs]))

(defrecord MessageSocket [buffer-socket in-ch out-ch])

(defn handle-backend-key-data [bs]
  (let [[id key] (buf/read bs (buf/spec buf/int32 buf/int32) {:offset 5})]
    {:type :BackendKeyData
     :id   id
     :key  key}))

(defn handle-ready-for-query [bs]
  (let [status (buf/read bs (buf/spec buf/byte) {:offset 5})]
    {:type   :ReadyForQuery
     :status status}))

(defn handle-parameter-status [bs]
  (let [status (buf/read bs (buf/spec codecs/cstring codecs/cstring) {:offset 5})]
    {:type   :ParameterStatus
     :status status}))

(defn handle-data-row [bs]
  (let [[r cols] (buf/read* bs buf/int16 {:offset 5})]
    (loop [i      0
           arr    []
           offset (+ 5 r)]
      (if (<= cols i)
        {:type     :DataRow
         :num-cols cols
         :values   arr}
        (let [[r1 len]  (buf/read* bs buf/int32 {:offset offset})
              offset    (+ r1 offset)
              [r2 data] (buf/read* bs (buf/repeat len  buf/byte) {:offset offset})
              offset    (+ r2 offset)]
          (recur (inc i) (conj arr {:idx  i
                                    :data data}) offset))))))

(defn handle-row-description [bs]
  (let [[r cols]  (buf/read* bs buf/int16 {:offset 5})
        offset    (+ 5 r)
        codec codecs/row-desc-codec]
    (if (< 0 cols)
      (let [[_ items] (buf/read* bs (buf/repeat cols codec) {:offset offset})]
        {:type     :RowDescription
         :num-cols cols
         :values   items})
      {:type     :RowDescription
       :num-cols cols
       :values   nil})))

(defn handle-authentication [bs]
  (let [offset 5
        [n auth-req] (buf/read* bs codecs/auth-req-codec {:offset offset})
        offset (+ offset n)]
    (m/match auth-req
      {:tag 0}
      {:type :AuthenticationOk}
      {:tag 5}
      {:type :AuthenticationMD5
       :salt (buf/read bs (buf/bytes 4) {:offset offset})})))

(defn handle-error
  ([bs] (handle-error bs 5 []))
  ([bs offset errs]
   (let [[r head] (buf/read* bs buf/byte {:offset offset})
         offset (+ r offset)]
     (if (< 0 head)
       (let [[r1 msg] (buf/read* bs codecs/cstring {:offset offset})
             offset (+ r1 offset)]
         (handle-error bs offset (conj errs msg)))
       {:type   :ErrorMessage
        :errors errs}))))

(defn handle-close [bs]
  (let [[close name] (buf/read bs buf/byte codecs/cstring {:offset 5})]
    {:type :Close :close close :name name}))

(defn handle-backend [bs]
  (let [header (buf/read bs codecs/header-codec)]
    (m/match header
      {:tag 90}
      (handle-ready-for-query bs)
      {:tag 84}
      (handle-row-description bs)
      {:tag 83}
      (handle-parameter-status bs)
      {:tag 82}
      (handle-authentication bs)
      {:tag 75}
      (handle-backend-key-data bs)
      {:tag 69}
      (handle-error bs)
      {:tag 68}
      (handle-data-row bs)
      {:tag 67}
      (handle-close bs)
      :else (let [unk (buf/read bs (buf/repeat (- (:len header) 4) buf/byte))]
              {:type    :Unknown
               :header  header
               :payload unk}))))

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
         in-ch         (async/chan)
         out-ch        (async/chan)
         message-sock  (map->MessageSocket {:buffer-socket buffer-socket
                                            :in            in-ch
                                            :out           out-ch})]

     (async/go-loop []
       (when #?(:clj (and (.isConnected (:channel buffer-socket)) (.isOpen (:channel buffer-socket))) :cljs true)
         (let [bbs (async/<! (:in buffer-socket))]
           (if-not bbs
             (bs/close-buffer-socket buffer-socket)
             (when (async/>! in-ch (handle-backend bbs))
               (recur))))))

     (async/go-loop []
       (when #?(:clj (and (.isConnected (:channel buffer-socket)) (.isOpen (:channel buffer-socket))) :cljs true)
         (let [message (async/<! out-ch)]
           (if-not message
             (bs/close-buffer-socket buffer-socket)
             (when (async/>! (:out buffer-socket) (handle-frontend message))
               (recur))))))

     message-sock)))

(defn close-message-socket [{:keys [in out buffer-socket]
                             :as   this}]
  (bs/close-buffer-socket buffer-socket)
  (async/close! in)
  (async/close! out)
  (assoc this :buffer-socket nil :in nil :out nil))

;; Example usage
(comment
  (def username "jimmy")
  (def password "banana")
  (def database "world")

  (def message-socket (get-message-socket 5432))

  (async/go-loop []
    (when-let [message (async/<! (:in message-socket))]
      (println message)
      (m/match message
        {:type :AuthenticationOk}
        ()
        {:type :AuthenticationMD5}
          ;; Respond with password
        (let [salt     #?(:clj (byte-array (:salt message)) :cljs (js/Int8Array. (map byte (:salt message))))
              password (pw/calculate-pw username password salt)]
          (async/>! (:out message-socket) {:type     :PasswordMessage
                                           :password password}))
        :else ())
      (recur)))

  (async/go
    (async/>! (:out message-socket) {:type     :StartupMessage
                                     :user     username
                                     :database database}))

  (close-message-socket message-socket))