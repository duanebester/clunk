(ns com.clunk.core
  (:require [clojure.core.match :as m]
            [clojure.core.async :as async]
            [com.clunk.pw :as pw]
            [com.clunk.message-socket :as ms]))

(defrecord Client [message-socket config in-ch out-ch])

(defn client [config]
  (let [host     (get config :hostname "localhost")
        port     (get config :port 5432)
        user     (get config :username "postgres")
        password (get config :password "")
        database (get config :database "postgres")
        msocket  (ms/get-message-socket port host)
        in-ch    (async/chan)
        out-ch   (async/chan)
        cfg      {:host host :port port :user user :password password :database database}
        c        (map->Client {:message-socket msocket :config cfg :in in-ch :out out-ch})]
    c))

(defn connect
  [^Client client]
  (async/go-loop []
    (when-let [message (async/<! (:in (:message-socket client)))]
      (println message)
      (m/match message
        {:type :AuthenticationOk}
        true
        {:type :AuthenticationMD5}
          ;; Respond with password
        (let [salt     (:salt message)
              password (pw/calculate-pw (:user (:config client)) (:password (:config client)) salt)]
          (async/>! (:out (:message-socket client)) {:type     :PasswordMessage
                                                     :password password}))
        :else false)
      (recur)))
  (async/>!! (:out (:message-socket client)) {:type     :StartupMessage
                                              :user     (:user (:config client))
                                              :database (:database (:config client))}))

;; Need to group DataRows and do the mashup with RowDescriptions
(defn query [^Client client message]
  (let [out {:type  :Query :query message}]
    (async/>!! (:out (:message-socket client)) out)
    (async/<!! (:in (:message-socket client)))))

(defn close [{:keys [in out message-socket]
              :as   this}]
  (ms/close-message-socket message-socket)
  (async/close! in)
  (async/close! out)
  (assoc this :message-socket nil :in nil :out nil))
