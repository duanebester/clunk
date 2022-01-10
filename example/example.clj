(require '[clunk.core :as clunk]
         '[clojure.core.async :as async]
         '[clojure.core.match :as m])

(def username "jimmy")
(def password "banana")
(def database "world")
(def port (int 5432))

(def client (clunk/socket-client port))

;; Receive Handler
(async/go-loop []
  (when-let [msg (async/<! (:in client))]
    (println (str "Received Message: " msg))
    (m/match msg
      {:type :AuthenticationMD5}
      (async/>! (:out client) (clunk/get-password username password (byte-array (:salt msg))))
      :else nil)
    (recur)))

(async/>!! (:out client) (clunk/get-startup username database))
;; (async/>!! (:out client) (clunk/get-query "select 1 as x, 2 as y;"))
(async/>!! (:out client) (clunk/get-query "select * from country limit 10;"))

(clunk/close-client client)