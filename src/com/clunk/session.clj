(ns com.clunk.session
  (:require [clojure.core.async :as async]
            [clojure.core.match :as m]
            [clojure.tools.logging :as log]
            [com.clunk.message-socket :as ms]
            [com.clunk.pw :as pw]))

(defrecord Session [message-socket in-ch out-ch state])

(defn init-session
  ([]
   (init-session "postgres" nil "postgres" 5432 "localhost"))
  ([username password database]
   (init-session username password database 5432 "localhost"))
  ([username password database port host]
   (let [message-socket (ms/get-message-socket port host)
         in-ch          (async/chan)
         out-ch         (async/chan)
         state          (atom {:status :Unknown})
         session        (map->Session {:message-socket message-socket
                                       :state          state
                                       :in             in-ch
                                       :out            out-ch})]

     ;; Connect / Auth
     (async/>!! (:out message-socket) {:type     :StartupMessage
                                       :user     username
                                       :database database})
     (loop []
       (when-let [message (async/<!! (:in message-socket))]
         (log/info message)
         (m/match message
           {:type :ErrorMessage}
           (do
             (swap! state assoc :state :Error)
             (log/error (:errors message)))
           {:type :AuthenticationOk}
           (swap! state assoc :state :AuthenticationOk)
           {:type :ReadyForQuery}
           (swap! state assoc :state :ReadyForQuery)
           {:type :AuthenticationMD5}
            ;; Respond with password
           (let [salt     (byte-array (:salt message))
                 password (pw/calculate-pw username password salt)]
             (async/>!! (:out message-socket) {:type     :PasswordMessage
                                               :password password }))
           :else ()))
       (if (or (= :ReadyForQuery (:state @state)) (= :Error (:state @state)))
         (log/info "Connected")
         (recur)))

     (async/go-loop []
       (let [p (async/<! (:in message-socket))]
         (if-not p
           (ms/close-message-socket message-socket)
           (when (async/>! in-ch p)
             (recur)))))

     (async/go-loop []
       (let [p (async/<! out-ch)]
         (if-not p
           (ms/close-message-socket message-socket)
           (do (m/match p
                 {:type :Query}
                 (async/>! (:out message-socket) p)
                 :else (log/info "Unknown " p))
               (recur)))))

     session)))

(defn query [qs] {:type  :Query
                  :query qs})

(defn close-session [{:keys [in out message-socket]
                      :as   this}]
  (log/info "Closing Session")
  (ms/close-message-socket message-socket)
  (async/close! in)
  (async/close! out)
  (assoc this :message-socket nil :in nil :out nil :state nil))

(def username "jimmy")
(def password "banana")
(def database "world")
(def session (init-session username password database))

(async/>!! (:out session) (query "SELECT name FROM country LIMIT 10;"))
(log/info (async/<!! (:in session)))

(close-session session)
