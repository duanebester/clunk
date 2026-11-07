(ns clunk.listen-test
  "LISTEN and NOTIFY against the Postgres from docker-compose.yml, on the JVM
   and on Node. Uses no tables."
  (:require #?(:clj [clojure.test :as t] :cljs [cljs.test :as t])
            [clojure.core.async :as async]
            [clunk.core :as clunk]
            [clunk.pool :as pool])
  #?(:clj (:import (java.util.concurrent CompletableFuture)
                   (java.util.function BiConsumer))))

(def config {:username "jimmy" :password "banana" :database "world"})

(defn- run
  "Runs a go block's channel to completion, failing the test after 10 s."
  [ch]
  (let [result (async/go (async/alt! ch ([v] v)
                                     (async/timeout 10000) (t/is false "timed out")))]
    #?(:clj (async/<!! result)
       :cljs (t/async done (async/take! result (fn [_] (done)))))))

(defn- settled
  "A channel that yields what a CompletableFuture or js/Promise settles to,
   or ::nil for nil."
  [p]
  (let [ch (async/promise-chan)]
    #?(:clj  (.whenComplete ^CompletableFuture p (reify BiConsumer
                                                   (accept [_ v e] (async/put! ch (or e v ::nil)))))
       :cljs (.then p #(async/put! ch (if (nil? %) ::nil %)) #(async/put! ch %)))
    ch))

(defn- within
  "Inside a go block, the next value of ch, nil once it closes, or ::timeout
   after ms."
  [ch ms]
  (async/go (async/alt! ch ([v] v) (async/timeout ms) ::timeout)))

(defn- connected []
  (async/go (let [c (clunk/client config)]
              (async/<! (clunk/connect c))
              c)))

(defn- pid [c]
  (async/go (:pid (first (clunk/<query! c "SELECT pg_backend_pid() AS pid")))))

(t/deftest notifications-arrive-while-idle-with-utf-8-text
  (run (async/go
         (let [a     (async/<! (connected))
               b     (async/<! (connected))
               ;; Quoted as an identifier, so case, spaces and quotes survive.
               name  "Clünk \"q\" 🐘"
               ch    (clunk/listen a name)
               _     (clunk/<query! a "SELECT 1") ;; queued after LISTEN, so it has run
               b-pid (async/<! (pid b))]
           (t/is (= ::nil (async/<! (settled (clunk/notify b name "naïve café 🐘 日本語")))))
           (t/is (= {:channel name :payload "naïve café 🐘 日本語" :pid b-pid} (async/<! (within ch 2000))))
           (async/<! (settled (clunk/notify b name "")))
           (t/is (= "" (:payload (async/<! (within ch 2000)))))
           ;; Other channels' notifications don't reach it.
           (async/<! (settled (clunk/notify b "clunk_other" "x")))
           (t/is (= ::timeout (async/<! (within ch 300))))
           (clunk/close a)
           (clunk/close b)))))

(t/deftest notifications-arrive-during-a-query
  (run (async/go
         (let [a      (async/<! (connected))
               b      (async/<! (connected))
               ch     (clunk/listen a "clunk_mid")
               _      (clunk/<query! a "SELECT 1")
               a-pid  (async/<! (pid a))]
           ;; The server sends a session its own notification after the
           ;; statement's rows and before it is ready again.
           (t/is (= [{:n 1} {:n 2} {:n 3}]
                    (clunk/<query! a "SELECT g::int4 AS n FROM pg_notify('clunk_mid', 'self'), generate_series(1, 3) g")))
           (t/is (= {:channel "clunk_mid" :payload "self" :pid a-pid} (async/<! (within ch 2000))))
           ;; Notified from elsewhere while a streamed query is between batches:
           ;; the server holds it until the query's transaction ends, and the
           ;; rows around it are untouched.
           (let [cancel (async/promise-chan)
                 rows   (clunk/query a ["SELECT g::int4 AS n FROM generate_series(1, $1) g" 100] {:cancel cancel :fetch-size 10})
                 head   (async/<! rows)]
             (async/<! (settled (clunk/notify b "clunk_mid" "from b")))
             (t/is (= (map (fn [n] {:n n}) (range 1 101))
                      (cons head (async/<! (async/into [] rows)))))
             (t/is (= "from b" (:payload (async/<! (within ch 2000))))))
           (clunk/close a)
           (clunk/close b)))))

(t/deftest a-slow-listener-does-not-hold-up-queries
  (run (async/go
         (let [a  (async/<! (connected))
               b  (async/<! (connected))
               ch (clunk/listen a "clunk_slow" {:max-queued 5})]
           (clunk/<query! a "SELECT 1")
           ;; Ten in one transaction, none taken yet.
           (clunk/<query! b "SELECT pg_notify('clunk_slow', g::text) FROM generate_series(1, 10) g")
           (t/is (= [{:n 7}] (clunk/<query! a "SELECT 7::int4 AS n")))
           (t/is (= ["1" "2" "3" "4" "5"]
                    (loop [ps []] (if (< (count ps) 5) (recur (conj ps (:payload (async/<! ch)))) ps))))
           ;; The sixth found five waiting, so the listener fell behind and closed.
           (let [err (async/<! ch)]
             (t/is (clunk/error? err))
             (t/is (= "Listener fell behind" (ex-message err))))
           (t/is (nil? (async/<! ch)))
           ;; Its UNLISTEN went once it was gone.
           (t/is (= [] (clunk/<query! a "SELECT pg_listening_channels() AS c")))
           (clunk/close a)
           (clunk/close b)))))

(t/deftest unlisten-and-closing-the-channel-stop-delivery
  (run (async/go
         (let [a   (async/<! (connected))
               b   (async/<! (connected))
               one (clunk/listen a "clunk_u")
               two (clunk/listen a "clunk_u")]
           (clunk/<query! a "SELECT 1")
           (async/<! (settled (clunk/notify b "clunk_u" "both")))
           (t/is (= "both" (:payload (async/<! (within one 2000)))))
           (t/is (= "both" (:payload (async/<! (within two 2000)))))
           ;; Unlistening one channel closes it, and the other still listens.
           (t/is (nil? (clunk/unlisten a one)))
           (t/is (nil? (async/<! (within one 2000))))
           (async/<! (settled (clunk/notify b "clunk_u" "two only")))
           (t/is (= "two only" (:payload (async/<! (within two 2000)))))
           ;; Unlistening the name ends the rest and stops the server.
           (async/<! (settled (clunk/unlisten a "clunk_u")))
           (t/is (nil? (async/<! (within two 2000))))
           (t/is (= [] (clunk/<query! a "SELECT pg_listening_channels() AS c")))
           (async/<! (settled (clunk/notify b "clunk_u" "nobody")))
           ;; A channel closed by its reader is dropped at its next notification.
           (let [ch (clunk/listen a "clunk_closed")]
             (clunk/<query! a "SELECT 1")
             (async/close! ch)
             (async/<! (settled (clunk/notify b "clunk_closed" "dropped")))
             (async/<! (async/timeout 200))
             (t/is (= [] (clunk/<query! a "SELECT pg_listening_channels() AS c"))))
           (clunk/close a)
           (clunk/close b)))))

(t/deftest a-dropped-connection-ends-its-listeners-with-an-error
  (run (async/go
         (let [a     (async/<! (connected))
               b     (async/<! (connected))
               ch    (clunk/listen a "clunk_drop")
               a-pid (async/<! (pid a))]
           (clunk/<query! a "SELECT 1")
           (clunk/<query! b (str "SELECT pg_terminate_backend(" a-pid ")"))
           (let [err (async/<! (within ch 2000))]
             (t/is (clunk/error? err))
             (t/is (= "Connection closed" (ex-message err))))
           (t/is (nil? (async/<! (within ch 2000))))
           ;; Listening on a client that's gone fails at once.
           (t/is (clunk/error? (async/<! (within (clunk/listen a "clunk_drop") 2000))))
           (clunk/close a)
           (clunk/close b)))))

(t/deftest a-pool-listens-on-a-connection-of-its-own
  (let [p (pool/pool (assoc config :max-size 1))]
    (run (async/go
           (let [ch     (clunk/listen p "clunk_pool")
                 pooled (async/<! (pid p))]
             ;; LISTEN has run once a query after it on the listener's connection has.
             (clunk/<query! @(:listen-client p) "SELECT 1")
             (t/is (= ::nil (async/<! (settled (clunk/notify p "clunk_pool" "hi")))))
             ;; Sent from the only pooled connection, so the listener's isn't lent.
             (t/is (= {:channel "clunk_pool" :payload "hi" :pid pooled} (async/<! (within ch 2000))))
             (t/is (= 1 (:size @(:state p))))
             (clunk/close p)
             (t/is (nil? (async/<! (within ch 2000))))
             (t/is (= "Pool is closed" (ex-message (async/<! (within (clunk/listen p "clunk_pool") 2000))))))))))
