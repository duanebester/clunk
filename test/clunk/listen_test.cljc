(ns clunk.listen-test
  "LISTEN and NOTIFY against the Postgres from docker-compose.yml, on the JVM
   and on Node. Uses no tables."
  (:require #?(:clj [clojure.test :as t] :cljs [cljs.test :as t])
            [clojure.core.async :as async]
            [clunk.core :as clunk]
            [clunk.pool :as pool])
  #?(:clj (:import (java.util.concurrent CompletableFuture CompletionException CountDownLatch)
                   (java.util.function BiConsumer))))

(def config {:username "jimmy" :password "banana" :database "world"})

(defn- run
  "Runs a go block's channel to completion, failing the test after 10 s."
  [ch]
  (let [result (async/go (async/alt! ch ([v] v)
                                     (async/timeout 10000) (t/is false "timed out")))]
    #?(:clj (async/<!! result)
       :cljs (t/async done (async/take! result (fn [_] (done)))))))

(defn- settle
  "A channel that yields what a CompletableFuture or js/Promise settles to,
   its value or its error, or ::nil for nil."
  [p]
  (let [ch (async/promise-chan)]
    #?(:clj  (.whenComplete ^CompletableFuture p (reify BiConsumer
                                                   (accept [_ v e]
                                                     (async/put! ch (cond (instance? CompletionException e) (ex-cause e)
                                                                          e e
                                                                          :else (if (nil? v) ::nil v))))))
       :cljs (.then p #(async/put! ch (if (nil? %) ::nil %)) #(async/put! ch %)))
    ch))

(defn- exec [c sql] (settle (clunk/execute c sql)))

(defn- listen
  "Listens with clunk.core/listen, and returns [handle ch]: ch gets each
   notification, and then the error the listener ended with or ::end."
  ([c channel-name] (listen c channel-name nil))
  ([c channel-name opts]
   (let [ch (async/chan 100)]
     [(clunk/listen c channel-name #(async/put! ch (if (nil? %) ::end %)) opts) ch])))

(defn- within
  "Inside a go block, the next value of ch, or ::timeout after ms."
  [ch ms]
  (async/go (async/alt! ch ([v] v) (async/timeout ms) ::timeout)))

(defn- connected []
  (async/go (let [c (clunk/client config)]
              (async/<! (settle (clunk/connect c)))
              c)))

(defn- pid [c]
  (async/go (:pid (first (async/<! (exec c "SELECT pg_backend_pid() AS pid"))))))

(t/deftest notifications-arrive-while-idle-with-utf-8-text
  (run (async/go
         (let [a      (async/<! (connected))
               b      (async/<! (connected))
               ;; Quoted as an identifier, so case, spaces and quotes survive.
               name   "Clünk \"q\" 🐘"
               [_ ch] (listen a name)
               _      (async/<! (exec a "SELECT 1")) ;; queued after LISTEN, so it has run
               b-pid  (async/<! (pid b))]
           (t/is (= ::nil (async/<! (settle (clunk/notify b name "naïve café 🐘 日本語")))))
           (t/is (= {:channel name :payload "naïve café 🐘 日本語" :pid b-pid} (async/<! (within ch 2000))))
           (async/<! (settle (clunk/notify b name "")))
           (t/is (= "" (:payload (async/<! (within ch 2000)))))
           ;; Other channels' notifications don't reach it.
           (async/<! (settle (clunk/notify b "clunk_other" "x")))
           (t/is (= ::timeout (async/<! (within ch 300))))
           ;; Closing the client ends the listener with no error.
           (clunk/close a)
           (t/is (= ::end (async/<! (within ch 2000))))
           (clunk/close b)))))

(t/deftest notifications-arrive-during-a-query
  (run (async/go
         (let [a      (async/<! (connected))
               [_ ch] (listen a "clunk_mid")
               _      (async/<! (exec a "SELECT 1"))
               a-pid  (async/<! (pid a))]
           ;; The server sends a session its own notification after the
           ;; statement's rows and before it is ready again.
           (t/is (= [{:n 1} {:n 2} {:n 3}]
                    (async/<! (exec a "SELECT g::int4 AS n FROM pg_notify('clunk_mid', 'self'), generate_series(1, 3) g"))))
           (t/is (= {:channel "clunk_mid" :payload "self" :pid a-pid} (async/<! (within ch 2000))))
           (clunk/close a)))))

#?(:clj
   (t/deftest a-slow-listener-does-not-hold-up-queries
     ;; f blocks at the first notification until the test lets it go, so the
     ;; rest wait in its queue.
     (let [a       (clunk/client config)
           b       (clunk/client config)
           _       @(clunk/connect a)
           _       @(clunk/connect b)
           started (promise)
           release (CountDownLatch. 1)
           got     (async/chan 100)]
       (clunk/listen a "clunk_slow"
                     (fn [x]
                       (when (= "first" (:payload x))
                         (deliver started true)
                         (.await release))
                       (async/>!! got (if (nil? x) ::end x)))
                     {:max-queued 5})
       @(clunk/execute a "SELECT 1")
       @(clunk/notify b "clunk_slow" "first")
       (t/is (deref started 2000 false))
       ;; Ten in one transaction, while f still holds the first.
       @(clunk/execute b "SELECT pg_notify('clunk_slow', g::text) FROM generate_series(1, 10) g")
       (t/is (= [{:n 7}] (deref (clunk/execute a "SELECT 7::int4 AS n") 2000 :timed-out)))
       ;; The sixth found five waiting, so the listener fell behind, and its
       ;; UNLISTEN went.
       (t/is (= [] @(clunk/execute a "SELECT pg_listening_channels() AS c")))
       (.countDown release)
       (t/is (= ["first" "1" "2" "3" "4" "5"] (repeatedly 6 #(:payload (async/<!! got)))))
       (let [err (async/<!! got)]
         (t/is (clunk/error? err))
         (t/is (= "Listener fell behind" (ex-message err))))
       (t/is (= ::timeout (async/<!! (within got 300))))
       (clunk/close a)
       (clunk/close b))))

(t/deftest unlisten-stops-delivery
  (run (async/go
         (let [a          (async/<! (connected))
               b          (async/<! (connected))
               [one ch1]  (listen a "clunk_u")
               [_ ch2]    (listen a "clunk_u")]
           (async/<! (exec a "SELECT 1"))
           (async/<! (settle (clunk/notify b "clunk_u" "both")))
           (t/is (= "both" (:payload (async/<! (within ch1 2000)))))
           (t/is (= "both" (:payload (async/<! (within ch2 2000)))))
           ;; Unlistening one ends it, and the other still listens.
           (t/is (nil? (clunk/unlisten a one)))
           (t/is (= ::end (async/<! (within ch1 2000))))
           (async/<! (settle (clunk/notify b "clunk_u" "two only")))
           (t/is (= "two only" (:payload (async/<! (within ch2 2000)))))
           (t/is (= ::timeout (async/<! (within ch1 300))))
           ;; Unlistening the name ends the rest and stops the server.
           (async/<! (settle (clunk/unlisten a "clunk_u")))
           (t/is (= ::end (async/<! (within ch2 2000))))
           (t/is (= [] (async/<! (exec a "SELECT pg_listening_channels() AS c"))))
           (t/is (nil? (clunk/unlisten a one)) "nothing left to stop")
           ;; A callback that throws is logged, and the next notification still comes.
           (let [ch (async/chan 10)]
             (clunk/listen a "clunk_throws" (fn [n]
                                              (when (= "boom" (:payload n)) (throw (ex-info "boom" {})))
                                              (when n (async/put! ch (:payload n)))))
             (async/<! (exec a "SELECT 1"))
             (async/<! (settle (clunk/notify b "clunk_throws" "boom")))
             (async/<! (settle (clunk/notify b "clunk_throws" "after")))
             (t/is (= "after" (async/<! (within ch 2000)))))
           (clunk/close a)
           (clunk/close b)))))

(t/deftest a-dropped-connection-ends-its-listeners-with-an-error
  (run (async/go
         (let [a      (async/<! (connected))
               b      (async/<! (connected))
               [_ ch] (listen a "clunk_drop")
               a-pid  (async/<! (pid a))]
           (async/<! (exec a "SELECT 1"))
           (async/<! (exec b (str "SELECT pg_terminate_backend(" a-pid ")")))
           (let [err (async/<! (within ch 2000))]
             (t/is (clunk/error? err))
             (t/is (= "Connection closed" (ex-message err))))
           (t/is (= ::timeout (async/<! (within ch 300))))
           ;; Listening on a client that's gone fails at once.
           (t/is (clunk/error? (async/<! (within (second (listen a "clunk_drop")) 2000))))
           (clunk/close a)
           (clunk/close b)))))

(t/deftest a-pool-listens-on-a-connection-of-its-own
  (let [p (pool/pool (assoc config :max-size 1))]
    (run (async/go
           (let [[_ ch] (listen p "clunk_pool")
                 pooled (async/<! (pid p))]
             ;; LISTEN has run once a query after it on the listener's connection has.
             (async/<! (exec @(:listen-client p) "SELECT 1"))
             (t/is (= ::nil (async/<! (settle (clunk/notify p "clunk_pool" "hi")))))
             ;; Sent from the only pooled connection, so the listener's isn't lent.
             (t/is (= {:channel "clunk_pool" :payload "hi" :pid pooled} (async/<! (within ch 2000))))
             (t/is (= 1 (:size @(:state p))))
             (clunk/close p)
             (t/is (= ::end (async/<! (within ch 2000))))
             (t/is (= "Pool is closed" (ex-message (async/<! (within (second (listen p "clunk_pool")) 2000))))))))))
