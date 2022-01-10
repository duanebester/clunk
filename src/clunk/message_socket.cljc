(ns clunk.message-socket
  (:require [clojure.core.match :as m]
            [clojure.core.async :as async]
            [clunk.pw :as pw]
            [clunk.messages :as messages]
            [clunk.codecs :as codecs]
            [clunk.byte-buffer-socket :as bs]))

#?(:cljs (enable-console-print!))

(defrecord MessageSocket [buffer-socket in-ch out-ch])

(defn connected?
  [^MessageSocket ms]
  (bs/connected? (:client (:buffer-socket ms))))

(defn handle-backend [bs]
  (let [header (codecs/rr-header bs)]
    (m/match header
      {:tag 90}
      (messages/handle-ready-for-query bs)
      {:tag 84}
      (messages/handle-row-description bs)
      {:tag 83}
      (messages/handle-parameter-status bs)
      {:tag 82}
      (messages/handle-authentication bs)
      {:tag 75}
      (messages/handle-backend-key-data bs)
      {:tag 69}
      (messages/handle-error bs)
      {:tag 68}
      (messages/handle-data-row bs)
      {:tag 67}
      (messages/handle-close bs)
      :else
      (messages/handle-unknown bs header))))

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
       (when (bs/connected? (:client buffer-socket))
         (let [bbs (async/<! (:in buffer-socket))]
           (if-not bbs
             (bs/close-buffer-socket buffer-socket)
             (when (async/>! in-ch (handle-backend bbs))
               (recur))))))

     (async/go-loop []
       (when (bs/connected? (:client buffer-socket))
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
  ;; clj -M -m cljs.main --target node --output-to ms.js -c com.clunk.message-socket && node ms.js
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
        (let [salt     (:salt message)
              password (pw/calculate-pw username password salt)]
          (async/>! (:out message-socket) {:type     :PasswordMessage
                                           :password password}))
        :else ())
      (recur)))

  (async/go
    (async/>! (:out message-socket) {:type     :StartupMessage
                                     :user     username
                                     :database database}))

  (async/go
    (async/>! (:out message-socket) {:type     :Query
                                     :query    "SELECT name FROM country LIMIT 3;"}))

  (close-message-socket message-socket))