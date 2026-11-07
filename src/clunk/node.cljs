(ns clunk.node
  "Promise API for JavaScript callers, exported by the shadow-cljs :node build."
  (:require [clojure.set :as set]
            [clunk.core :as clunk]
            [clunk.message-socket :as ms]
            [clunk.pool :as pool]))

(defn- js-error
  "A plain JS Error for a clunk error, with the Postgres fields (code,
   severity) and the sql as properties, as node-postgres has them, and the
   column and oid of a value that failed to decode."
  [e]
  (let [err (js/Error. (ex-message e))]
    (doseq [[k v] (select-keys (ex-data e) [:code :severity :sql :column :oid])]
      (aset err (name k) v))
    err))

(defn- clj-config
  "A JS config object as clunk.core/client takes it. sslOptions, Node
   tls.connect options, stay a JS object."
  [^js config]
  (-> (js->clj config :keywordize-keys true)
      (dissoc :sslOptions)
      (set/rename-keys {:maxSize        :max-size
                        :sslRootCert    :ssl-root-cert
                        :sslCert        :ssl-cert
                        :sslKey         :ssl-key
                        :sslServername  :ssl-servername
                        :sslNegotiation :ssl-negotiation})
      (cond-> (.-sslOptions config) (assoc :ssl-options (.-sslOptions config)))))

(defn connect
  "connect({hostname, port, username, password, database}) resolves to a
   client once the server is ready for queries. For TLS, add ssl: 'disable',
   'prefer', 'require', 'verify-ca' or 'verify-full' (or true, for
   'verify-full'), and as needed sslRootCert, sslCert and sslKey (each a file
   name, PEM text or a Buffer), sslServername, sslNegotiation: 'direct', and
   sslOptions for tls.connect."
  [config]
  (let [c (clunk/client (clj-config config))]
    (.then (clunk/connect c)
           (fn [_] c)
           (fn [e] (clunk/close c) (throw (js-error e))))))

(defn pool
  "pool({hostname, port, username, password, database, maxSize}) returns a
   pool of up to maxSize (default 10) clients that connect when queries need
   them. query and stream take it in place of a client. It takes connect's
   TLS settings too."
  [config]
  (pool/pool (clj-config config)))

(defn- sql-params
  "sql with the values in a params array, null or undefined for none."
  [sql params]
  (if params (into [sql] params) sql))

;; query's sink: collects the rows, which the row decoder has made JS
;; objects, straight into the array query resolves to.
(deftype Rows [rows ok fail ^:mutable err]
  clunk/Sink
  (-row! [_ _ row] (.push rows row) true)
  (-flush! [_ _] true)
  (-error! [_ _ e] (when-not err (set! err e)) true)
  (-end! [_ _] (if err (fail (js-error err)) (ok rows))))

(defn query
  "query(client or pool, sql, params?) resolves to an array of row objects.
   params is an array of the values of $1, $2, ... in sql."
  ([client sql] (query client sql nil))
  ([client sql params]
   (js/Promise. (fn [ok fail]
                  (clunk/query client (sql-params sql params) (Rows. #js [] ok fail nil) {:js-rows? true})))))

(def ^:private read-ahead
  "Rows a stream holds for its reader before it pauses the connection, which
   then pushes back on the server. Under a batch, so leaving early doesn't
   ask the server for another."
  64)

;; What stream and listen read from: a Sink for stream's rows, which the row
;; decoder has made JS objects, and a Listener for listen's notifications,
;; made JS objects as they arrive. It holds them until next takes them.
;;   buf      items not yet taken
;;   waiting  the resolve and reject of a next made while buf was empty
;;   ended    true once the query or listener has ended, after err if any
;;   stopped  true once the reader has left; nothing more is taken
;;   paused   the message socket the stream paused, until next resumes it
;;   max-queued  how many notifications may wait before a listener falls behind
;;   on-stop  called with the feed when the reader leaves
(deftype Feed [buf ^:mutable waiting ^:mutable ended ^:mutable err ^:mutable stopped ^:mutable paused max-queued on-stop]
  Object
  (put [_ x]
    (if waiting
      (let [[ok] waiting]
        (set! waiting nil)
        (ok #js {:done false :value x}))
      (.push buf x)))
  (settle [_]
    (when (and waiting (zero? (.-length buf)) (or err ended))
      (let [[ok fail] waiting
            e         err]
        (set! waiting nil)
        (set! err nil)
        (if e (fail (js-error e)) (ok #js {:done true})))))
  (resume [_]
    (when-let [ms paused]
      (set! paused nil)
      (ms/resume ms)))
  (take [this]
    (if (pos? (.-length buf))
      (let [x (.shift buf)]
        ;; Resumed only once all are taken, as rows then arrive in runs.
        (when (zero? (.-length buf)) (.resume this))
        (js/Promise.resolve #js {:done false :value x}))
      (js/Promise. (fn [ok fail]
                     (set! waiting [ok fail])
                     (.settle this)))))
  (stop [this]
    (when-not stopped
      (set! stopped true)
      (set! (.-length buf) 0)
      (on-stop this)
      ;; The connection must read on, so it can skip the rest.
      (.resume this))
    (set! ended true)
    (.settle this))

  clunk/Sink
  (-row! [this ms row]
    (when-not stopped
      (.put this row)
      (when (and ms (nil? paused) (<= read-ahead (.-length buf)))
        (ms/pause ms)
        (set! paused ms))
      true))
  (-flush! [_ _] (not stopped))
  (-error! [this _ e]
    (when-not stopped
      (set! err e)
      (.settle this)
      true))
  (-end! [this _]
    (set! ended true)
    (.settle this))

  clunk/Listener
  (-notify! [this n]
    (cond stopped                        false
          (<= max-queued (.-length buf)) :full
          :else                          (do (.put this (clj->js n)) true)))
  (-stop! [this e]
    (set! err e)
    (set! ended true)
    (.settle this)))

(defn- feed [max-queued on-stop]
  (Feed. #js [] nil false nil false nil max-queued on-stop))

(defn- iterator
  "An async iterable that reads from feed f, for for-await, Readable.from
   and ReadableStream.from. Leaving (break, a destroyed stream) stops f."
  [^js f]
  (let [it #js {:next   (fn [] (.take f))
                :return (fn [] (.stop f) (js/Promise.resolve #js {:done true}))}]
    (aset it js/Symbol.asyncIterator (fn [] it))
    it))

(defn stream
  "stream(client or pool, sql, params?) is an async iterable of row objects,
   for for-await and Readable.from. The server sends rows in batches, so
   leaving early (break, or a destroyed stream) stops it and lets the next
   query run; on a pool, it frees the connection. A reader that falls
   behind pauses the connection, so the server waits for it. A failed query
   rejects."
  ([client sql] (stream client sql nil))
  ([client sql params]
   (let [token (clunk/cancel-token)
         f     (feed nil (fn [_] (clunk/cancel! token)))]
     (clunk/query client (sql-params sql params) f {:cancel token :js-rows? true})
     (iterator f))))

(defn listen
  "listen(client or pool, channel) is an async iterable of the notifications
   sent on a LISTEN channel, each {channel, payload, pid}, for for-await.
   Leaving the loop (break) stops listening. It rejects if the connection
   drops or the listener falls 1000 notifications behind."
  [client channel]
  (let [f (feed 1000 (fn [f] (some-> (clunk/unlisten client f) (.catch (fn [_])))))]
    (clunk/add-listener! client channel f f)
    (iterator f)))

(defn unlisten
  "unlisten(client or pool, channel) ends every listen on channel, and
   resolves once the server has stopped sending its notifications."
  [client channel]
  (-> (or (clunk/unlisten client channel) (js/Promise.resolve nil))
      (.then (fn [_] js/undefined) #(throw (js-error %)))))

(defn notify
  "notify(client or pool, channel, payload) sends payload, a string, to the
   channel's listeners with pg_notify."
  [client channel payload]
  (.then (clunk/notify client channel payload)
         (fn [_] js/undefined)
         #(throw (js-error %))))

(defn close [client]
  (clunk/close client)
  nil)
