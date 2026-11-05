(ns clunk.core
  (:require [clojure.core.match :as m]
            [clojure.core.async :as async]
            [clunk.pw :as pw]
            [clunk.oid-types :refer [oid-converter]]
            [clunk.message-socket :as ms]))

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
  (let [{:keys [user password debug]} (:config client)
        out (:out (:message-socket client))]
    ;; scram holds the in-flight SCRAM-SHA-256 exchange between auth messages.
    (async/go-loop [scram nil]
      (when-let [message (async/<! (:in (:message-socket client)))]
        (when debug
          (println message))
        (case (:type message)
          :ReadyForQuery
          nil

          :AuthenticationMD5
          (do (async/>! out {:type     :PasswordMessage
                             :password (pw/calculate-pw user password (:salt message))})
              (recur scram))

          :AuthenticationSASL
          (if (some #{"SCRAM-SHA-256"} (:mechanisms message))
            (let [first-msg (pw/scram-client-first)]
              (async/>! out {:type      :SASLInitialResponse
                             :mechanism "SCRAM-SHA-256"
                             :data      (:message first-msg)})
              (recur first-msg))
            (println "Unsupported SASL mechanisms:" (:mechanisms message)))

          :AuthenticationSASLContinue
          (let [final-msg (pw/scram-client-final (assoc scram
                                                        :password password
                                                        :server-first (:data message)))]
            (async/>! out {:type :SASLResponse :data (:message final-msg)})
            (recur final-msg))

          :AuthenticationSASLFinal
          (if (pw/scram-server-final-valid? scram (:data message))
            (recur nil)
            (println "SCRAM server signature mismatch; refusing to continue"))

          (recur scram)))))
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
