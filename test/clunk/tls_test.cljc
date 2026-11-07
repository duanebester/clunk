(ns clunk.tls-test
  "TLS, on the JVM and on Node, against the Postgres from docker-compose.yml
   once test/tls/configure-postgres.sh has turned TLS on in it. Uses no
   tables, and logs in as clunk_ssl, whom pg_hba.conf lets in only over TLS."
  (:require #?(:clj [clojure.test :as t] :cljs [cljs.test :as t])
            [clojure.core.async :as async]
            [clunk.core :as clunk]
            [clunk.messages :as messages]
            [clunk.pool :as pool]
            [clunk.vendor.binf :as binf]
            #?@(:cljs [["fs" :as fs] ["net" :as net]]))
  #?(:clj (:import (clojure.lang ExceptionInfo)
                   (java.net ServerSocket Socket)
                   (java.util.concurrent CompletableFuture))))

(def certs "test/tls/certs/")

(def config
  {:username "clunk_ssl" :password "clunk_ssl" :database "world"
   :ssl :verify-full :ssl-root-cert (str certs "ca.crt")})

(def plain-config {:username "jimmy" :password "banana" :database "world"})

(defn- run
  "Runs a go block's channel to completion, failing the test after 20 s."
  [ch]
  (let [result (async/go (async/alt! ch ([v] v)
                                     (async/timeout 20000) (t/is false "timed out")))]
    #?(:clj (async/<!! result)
       :cljs (t/async done (async/take! result (fn [_] (done)))))))

(defn- settle
  "A channel that yields [:ok value] or [:error e] once execute's future or
   promise settles."
  [p]
  (let [ch (async/promise-chan)]
    #?(:clj  (.whenComplete ^CompletableFuture p (fn [v e] (async/put! ch (if e [:error e] [:ok v]))))
       :cljs (.then p #(async/put! ch [:ok %]) #(async/put! ch [:error %])))
    ch))

(defn- connect!
  "A channel that yields a connected client, or the error that stopped it:
   on the JVM, client throws it while it connects; on Node, connect yields it."
  [cfg]
  (async/go
    (let [c (async/<! #?(:clj  (async/thread (try (clunk/client cfg) (catch Exception e e)))
                         :cljs (async/to-chan! [(clunk/client cfg)])))]
      (if (instance? #?(:clj Throwable :cljs js/Error) c)
        c
        (let [[_ ready] (async/<! (settle (clunk/connect c)))]
          (if (clunk/error? ready)
            (do (clunk/close c) ready)
            c))))))

(defn- failed? [x]
  (instance? #?(:clj Throwable :cljs js/Error) x))

(defn- message [x]
  (when (failed? x) (ex-message x)))

(defn- exec
  "A channel that yields execute's rows, or its error."
  ([c sql] (exec c sql nil))
  ([c sql opts] (async/go (second (async/<! (settle (clunk/execute c sql opts)))))))

(defn- tls?
  "Inside a go block: whether the client's connection is encrypted, as the
   server sees it."
  [c]
  (async/go (:ssl (first (async/<! (exec c "SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()"))))))

(defn- fake-server
  "Listens on a free port and answers each connection's SSLRequest with
   answer, a string. With proxy?, the rest of the connection then goes to the
   real Postgres, as if it were a server without TLS. Yields {:port :close}."
  [answer proxy?]
  #?(:clj
     (let [ss    (ServerSocket. 0)
           pipe  (fn [^Socket from ^Socket to]
                   (async/thread
                     (try (.transferTo (.getInputStream from) (.getOutputStream to))
                          (catch Exception _))
                     (.close from)
                     (.close to)))]
       (async/thread
         (loop []
           (when-let [s (try (.accept ss) (catch Exception _ nil))]
             (async/thread
               (.readNBytes (.getInputStream s) 8)
               (doto (.getOutputStream s) (.write (.getBytes ^String answer "ISO-8859-1")) (.flush))
               (if proxy?
                 (let [up (Socket. "localhost" 5432)]
                   (pipe s up)
                   (pipe up s))
                 (pipe s s))) ;; holds it open until the client hangs up
             (recur))))
       (async/to-chan! [{:port (.getLocalPort ss) :close #(.close ss)}]))
     :cljs
     (let [ch     (async/promise-chan)
           server (net/createServer
                   (fn [^js s]
                     (.on s "error" (fn [_]))
                     (.once s "data"
                            (fn [_]
                              (.write s answer "latin1")
                              (when proxy?
                                (let [up (net/createConnection 5432 "localhost")]
                                  (.on up "error" (fn [_] (.destroy s)))
                                  (.pipe s up)
                                  (.pipe up s)))))))]
       (.listen server 0 "127.0.0.1"
                (fn [] (async/put! ch {:port (.-port (.address server)) :close #(.close server)})))
       ch)))

(defn- unsigned [view]
  (mapv #(bit-and % 0xff) #?(:clj (binf/backing-buffer view) :cljs (js/Uint8Array. (binf/backing-buffer view)))))

(t/deftest ssl-request-is-its-length-and-code
  (t/is (= [0 0 0 8 0x04 0xd2 0x16 0x2f] (unsigned (messages/build-ssl-request)))))

(t/deftest queries-portals-and-cancel-run-over-tls
  (run (async/go
         (let [refused (async/<! (connect! (assoc config :ssl :disable)))]
           (t/is (re-find #"no encryption" (str (message refused))) "clunk_ssl may only connect over TLS"))
         (let [c (async/<! (connect! config))]
           (t/is (not (failed? c)) (message c))
           (t/is (true? (async/<! (tls? c))))
           ;; About 2.4 MB: many TLS records, with messages cut across them.
           (let [rows (async/<! (exec c "SELECT g::int4 AS n, repeat('x', 100)::varchar AS s FROM generate_series(1, 20000) g"))]
             (t/is (= 20000 (count rows)))
             (t/is (= (range 1 20001) (map :n rows))))
           (t/is (= [{:n 42 :s "Zürich"}] (async/<! (exec c ["SELECT $1::int4 AS n, $2::text AS s" 42 "Zürich"]))))
           ;; A portal that fails in its fifth batch of 100.
           (t/is (= "22012" (:code (ex-data (async/<! (exec c "SELECT (1 / (450 - g))::int4 AS x FROM generate_series(1, 1000) g"
                                                            {:fetch-size 100}))))))
           ;; Cancelling stops the server after the first batch of 1000 rows.
           (async/<! (exec c "CREATE TEMP SEQUENCE tls_rows"))
           (let [token  (clunk/cancel-token)
                 result (exec c "SELECT nextval('tls_rows')::int4 AS n FROM generate_series(1, 100000)" {:cancel token})]
             (clunk/cancel! token)
             (t/is (= {:cancelled true} (ex-data (async/<! result))))
             (t/is (= [{:n 1000}] (async/<! (exec c "SELECT last_value::int4 AS n FROM tls_rows")))))
           (t/is (= [:ok [{:n 7}]] (async/<! (settle (clunk/execute c "SELECT 7::int4 AS n")))))
           (clunk/close c)))))

(t/deftest a-pool-runs-over-tls-and-reports-tls-errors
  (run (async/go
         (let [p (pool/pool (assoc config :max-size 2))]
           ;; Two at a time, four half-second queries take 1 s.
           (let [chs  (doall (repeatedly 4 #(exec p "SELECT ssl, pg_backend_pid() AS pid FROM pg_stat_ssl, pg_sleep(0.5) WHERE pid = pg_backend_pid()")))
                 rows (loop [[ch & more] chs acc []] (if ch (recur more (into acc (async/<! ch))) acc))]
             (t/is (= 4 (count rows)))
             (t/is (every? :ssl rows))
             (t/is (= 2 (count (set (map :pid rows))))))
           ;; Cancelling between portal batches gives the connection back.
           (let [token  (clunk/cancel-token)
                 result (exec p ["SELECT generate_series(1, $1)::int4 AS n" 5000] {:cancel token :fetch-size 10})]
             (clunk/cancel! token)
             (t/is (= {:cancelled true} (ex-data (async/<! result))))
             (t/is (= [:ok [{:n 2}]] (async/<! (settle (clunk/execute p "SELECT 2::int4 AS n"))))))
           ;; A TLS connection the server drops, during its own query, is replaced.
           (let [rows (async/<! (exec p "SELECT pg_terminate_backend(pg_backend_pid())"))]
             (t/is (clunk/error? rows))
             (t/is (= [true] (map :ssl (async/<! (exec p "SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()"))))))
           (clunk/close p))
         ;; A failed handshake fails the query, and execute, that needed the connection.
         (let [p (pool/pool (assoc config :ssl-root-cert (str certs "other-ca.crt")))]
           (t/is (re-find #"TLS handshake failed" (str (message (async/<! (exec p "SELECT 1"))))))
           (let [[status err] (async/<! (settle (clunk/execute p "SELECT 1")))]
             (t/is (= :error status))
             (t/is (re-find #"TLS handshake failed" (str (message err)))))
           (clunk/close p)))))

(t/deftest verify-full-rejects-a-wrong-hostname-and-an-untrusted-ca
  (run (async/go
         ;; The server's certificate names localhost only.
         (let [by-ip (assoc config :hostname "127.0.0.1")]
           (t/is (re-find #"TLS handshake failed.*127\.0\.0\.1" (str (message (async/<! (connect! by-ip))))))
           (doseq [ok [(assoc by-ip :ssl :verify-ca)
                       (assoc by-ip :ssl-servername "localhost")]]
             (let [c (async/<! (connect! ok))]
               (t/is (not (failed? c)) (str ok " " (message c)))
               (when-not (failed? c) (clunk/close c)))))
         (doseq [bad [(assoc config :ssl-root-cert (str certs "other-ca.crt"))
                      (dissoc config :ssl-root-cert) ;; the platform's CAs
                      (assoc config :ssl :require :ssl-root-cert (str certs "other-ca.crt"))
                      (assoc config :ssl true :ssl-root-cert nil)]]
           (t/is (re-find #"TLS handshake failed" (str (message (async/<! (connect! bad))))) (str bad)))
         ;; :require and :prefer alone encrypt without checking the certificate.
         (doseq [mode [:require :prefer]]
           (let [c (async/<! (connect! (-> config (assoc :ssl mode :hostname "127.0.0.1") (dissoc :ssl-root-cert))))]
             (t/is (true? (async/<! (tls? c))) (str mode))
             (clunk/close c))))))

(t/deftest prefer-falls-back-to-plain-and-require-fails-when-the-server-says-no
  (run (async/go
         (let [{:keys [port close]} (async/<! (fake-server "N" true))
               via (assoc plain-config :hostname "127.0.0.1" :port port)
               c   (async/<! (connect! (assoc via :ssl :prefer)))]
           (t/is (not (failed? c)) (message c))
           (t/is (false? (async/<! (tls? c))))
           (clunk/close c)
           (doseq [mode [:require :verify-full]]
             (t/is (= (str "The server does not support TLS, which :ssl " mode " needs")
                      (some->> (async/<! (connect! (assoc via :ssl mode))) message (re-find #"The server does not.*"))))
             (let [p (pool/pool (assoc via :ssl mode))]
               (t/is (re-find #"does not support TLS" (str (message (async/<! (exec p "SELECT 1"))))))
               (clunk/close p)))
           (close))
         ;; Bytes after S arrive before TLS starts, so anyone could have sent them.
         (let [{:keys [port close]} (async/<! (fake-server "Sgarbage" false))]
           (t/is (re-find #"unencrypted data|TLS handshake failed"
                          (str (message (async/<! (connect! (assoc plain-config :hostname "127.0.0.1" :port port :ssl :require)))))))
           (close)))))

(t/deftest client-certificates-and-direct-negotiation
  (run (async/go
         (let [cert-config (assoc config
                                  :username "clunk_cert" :password nil
                                  ;; PEM text works in place of a file name.
                                  :ssl-root-cert #?(:clj (slurp (str certs "ca.crt")) :cljs (str (fs/readFileSync (str certs "ca.crt")))))
               c (async/<! (connect! (assoc cert-config :ssl-cert (str certs "client.crt") :ssl-key (str certs "client.key"))))]
           (t/is (= [{:u "clunk_cert"}] (async/<! (exec c "SELECT current_user AS u"))))
           (clunk/close c)
           (t/is (re-find #"certificate" (str (message (async/<! (connect! cert-config)))))))
         ;; Direct negotiation needs Postgres 17.
         (let [c       (async/<! (connect! plain-config))
               version (:v (first (async/<! (exec c "SELECT current_setting('server_version_num')::int4 AS v"))))]
           (clunk/close c)
           (when (<= 170000 version)
             (let [d (async/<! (connect! (assoc config :ssl-negotiation :direct)))]
               (t/is (true? (async/<! (tls? d))))
               (clunk/close d))))
         (t/is (thrown-with-msg? ExceptionInfo #"needs :ssl :require"
                                 (clunk/client (assoc config :ssl :prefer :ssl-negotiation :direct))))
         (t/is (thrown-with-msg? ExceptionInfo #"Unknown :ssl mode"
                                 (clunk/client (assoc config :ssl :verify)))))))
