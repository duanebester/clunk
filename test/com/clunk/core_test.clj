(ns com.clunk.core-test
  (:require [com.clunk.core :as clunk]
            [clojure.test :as test]
            [clojure.core.async :as async]))

;; REQUIRES local postgres database to be running (docker-compose)
(def username "jimmy")
(def password "banana")
(def database "world")
(def port (int 5432))

(defn get-next-message [client]
  (let [msg (async/<!! (:in client))]
    (println (str "Received Message: " msg))
    msg))

(test/deftest test-auth-query
  (let [client (clunk/socket-client port)]
    (try
      ;; Send Startup Message
      (async/>!! (:out client) (clunk/get-startup username database))

      ;; Get MD5 Salt
      (let [md5-message (get-next-message client)]
        (test/is (= :AuthenticationMD5 (:type md5-message)))

        ;; Send MD5 & Password
        (async/>!! (:out client) (clunk/get-password username password (byte-array (:salt md5-message)))))

      ;; Get Auth Ok 
      (test/is (= :AuthenticationOk (:type (get-next-message client))))

      ;; Many :ParameterStatus Messages...
      (get-next-message client)
      (get-next-message client)
      (get-next-message client)
      (get-next-message client)
      (get-next-message client)
      (get-next-message client)
      (get-next-message client)
      (get-next-message client)
      (get-next-message client)
      (get-next-message client)
      (get-next-message client)
      (test/is (= :BackendKeyData (:type (get-next-message client))))

      ;; Now ready to query
      (test/is (= :ReadyForQuery (:type (get-next-message client))))

      ;; Run Query
      (async/>!! (:out client) (clunk/get-query "select 1 as x, 2 as y;"))

      ;; Query Results
      (test/is (= :RowDescription (:type (get-next-message client))))
      (test/is (= :DataRow (:type (get-next-message client))))
      (test/is (= :Close (:type (get-next-message client))))

      ;; Now ready to query
      (test/is (= :ReadyForQuery (:type (get-next-message client))))

      (finally
        (clunk/close-client client)))))