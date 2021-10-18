(ns com.clunk.core
  (:require [clojure.core.match :as m]
            [clojure.core.async :as async]
            [com.clunk.pw :as pw]
            [com.clunk.oid-types :refer [oid-converter]]
            [com.clunk.message-socket :as ms]))

(defrecord Client [message-socket config in-ch out-ch])

(defn client [config]
  (let [host     (get config :hostname "localhost")
        port     (get config :port 5432)
        user     (get config :username "postgres")
        password (get config :password "")
        database (get config :database "postgres")
        debug    (get config :debug false)
        msocket  (ms/get-message-socket port host)
        in-ch    (async/chan)
        out-ch   (async/chan)
        cfg      {:debug debug :host host :port port :user user :password password :database database}
        c        (map->Client {:message-socket msocket :config cfg :in in-ch :out out-ch})]
    c))

(defn connect
  [^Client client]
  (async/go-loop []
    (when-let [message (async/<! (:in (:message-socket client)))]
      (when (:debug (:config client))
        (println message))
      (when (m/match message
              {:type :ReadyForQuery}
              false
              {:type :AuthenticationOk}
              true
              {:type :AuthenticationMD5}
          ;; Respond with password
              (let [salt     (:salt message)
                    password (pw/calculate-pw (:user (:config client)) (:password (:config client)) salt)]
                (async/>! (:out (:message-socket client)) {:type     :PasswordMessage
                                                           :password password}))
              :else true)
        (recur))))
  (async/>!! (:out (:message-socket client)) {:type     :StartupMessage
                                              :user     (:user (:config client))
                                              :database (:database (:config client))}))

(defn close [{:keys [in out message-socket]
              :as   this}]
  (ms/close-message-socket message-socket)
  (async/close! in)
  (async/close! out)
  (assoc this :message-socket nil :in nil :out nil))

;; (def s (atom {}))
;; (swap! s assoc :duane "there")
;; (swap! s conj {:keem "there"})
;; (println @s)

(defn handle-dr
  [state row out]
  (let [rvalues (:values row)
        dvalues (:values state)]
    (async/>!! out
               (into {} (for [rv rvalues]
                          (if (nil? rv) nil
                              (let [dv (nth dvalues (:idx rv))
                                    fname (keyword (:field-name dv))
                                    data (oid-converter (:oid dv) (:data rv) (:length rv))]
                                {fname data})))))))

(defn _query
  [^Client client message]
  (let [state (atom {})
        out (async/chan)]
    (async/go-loop []
      (when-let [recv (async/<! (:in (:message-socket client)))]
        #_(when (:debug (:config client))
          (println (str "Query RECV: " recv)))
        (m/match recv
          {:type :ReadyForQuery}
          (async/close! out)
          {:type :Close}
          (reset! state {})
          {:type :RowDescription}
          (swap! state conj recv)
          {:type :DataRow}
          (handle-dr @state recv out)
          :else (println "Err: " recv))
        (recur)))
    (async/>!! (:out (:message-socket client)) {:type :Query :query message})
    out))

(def query (memoize _query))

(defmacro <query!
  [client qs]
  `(async/<! (query ~client ~qs)))
