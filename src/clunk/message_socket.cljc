(ns clunk.message-socket
  (:require [clojure.core.async :as async]
            [clunk.pw :as pw]
            [clunk.messages :as messages]
            [clunk.codecs :as codecs]
            [clunk.framing :as framing]
            [clunk.byte-buffer-socket :as bs]))

#?(:cljs (enable-console-print!))

(defrecord MessageSocket [buffer-socket in-ch out-ch])

(defn connected?
  [^MessageSocket ms]
  (bs/connected? (:client (:buffer-socket ms))))

(defn handle-backend [bs]
  (let [header (codecs/rr-header bs)]
    (case (int (:tag header))
      90 (messages/handle-ready-for-query bs)
      84 (messages/handle-row-description bs)
      83 (messages/handle-parameter-status bs)
      82 (messages/handle-authentication bs)
      75 (messages/handle-backend-key-data bs)
      69 (messages/handle-error bs)
      68 (messages/handle-data-row bs)
      67 (messages/handle-close bs)
      (messages/handle-unknown bs header))))

(defn handle-frontend [message]
  (case (:type message)
    :StartupMessage      (messages/build-startup (:user message) (:database message))
    :Query               (messages/build-query (:query message))
    :PasswordMessage     (messages/build-password (:password message))
    :SASLInitialResponse (messages/build-sasl-initial-response (:mechanism message) (:data message))
    :SASLResponse        (messages/build-sasl-response (:data message))))

(defn- decode
  "handle-backend, except that a message that fails to decode becomes an error
   message instead of an exception, so the bytes after it still frame."
  [view]
  (try (handle-backend view)
       (catch #?(:clj Exception :cljs :default) e
         {:type :ErrorMessage :message (str "Could not decode message: " (ex-message e))})))

(defn get-message-socket
  "Every translation happens in the channels' transducers, as bytes are put on
   :in by the socket and as messages are put on :out by the caller, so nothing
   sits between the socket and its reader."
  ([port] (get-message-socket port "localhost"))
  ([port host]
   (let [in  (async/chan 64 (comp (framing/frames) (map decode)))
         out (async/chan 64 (map handle-frontend))]
     (map->MessageSocket {:buffer-socket (bs/get-buffer-socket port host in out)
                          :in            in
                          :out           out}))))

(defn close-message-socket [{:keys [in out buffer-socket]
                             :as   this}]
  (bs/close-buffer-socket buffer-socket)
  (async/close! in)
  (async/close! out)
  (assoc this :buffer-socket nil :in nil :out nil))

;; Example usage
(comment
  ;; clj -M -m cljs.main --target node --output-to ms.js -c clunk.message-socket && node ms.js
  (def username "jimmy")
  (def password "banana")
  (def database "world")

  (def message-socket (get-message-socket 5432))

  (async/go-loop []
    (when-let [message (async/<! (:in message-socket))]
      (println message)
      (case (:type message)
        :AuthenticationMD5
        ;; Respond with password
        (let [salt     (:salt message)
              password (pw/calculate-pw username password salt)]
          (async/>! (:out message-socket) {:type     :PasswordMessage
                                           :password password}))
        nil)
      (recur)))

  (async/go
    (async/>! (:out message-socket) {:type     :StartupMessage
                                     :user     username
                                     :database database}))

  (async/go
    (async/>! (:out message-socket) {:type     :Query
                                     :query    "SELECT name FROM country LIMIT 3;"}))


  (close-message-socket message-socket))