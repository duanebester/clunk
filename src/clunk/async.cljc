(ns clunk.async
  "clunk's core.async API: query as a channel of rows, <query!, connect as
   a channel, and listen as a channel of notifications, built on the sinks,
   cancel tokens and listeners of clunk.core. clunk doesn't depend on
   core.async, so add org.clojure/core.async to your own dependencies to
   use this namespace."
  (:require [clojure.core.async :as async]
            #?(:clj  [clojure.core.async.impl.protocols :as async-impl]
               :cljs [cljs.core.async.impl.protocols :as async-impl])
            [clunk.core :as clunk]
            [clunk.message-socket :as ms])
  #?(:clj (:import (java.util.concurrent CompletableFuture)
                   (java.util.function BiConsumer)))
  #?(:cljs (:require-macros [clunk.async])))

(defn connect
  "Starts the connection, as clunk.core/connect does, and returns a channel
   that yields the server's ReadyForQuery message, or an error."
  [client]
  (let [ch (async/promise-chan)
        p  (clunk/connect client)]
    #?(:clj  (.whenComplete ^CompletableFuture p (reify BiConsumer (accept [_ v e] (async/put! ch (or e v)))))
       :cljs (.then p #(async/put! ch %) #(async/put! ch %)))
    ch))

(def ^:private batch-size
  "Most rows a channel sink puts on its channel at once. The channel takes a
   batch only once its reader has taken every row of the last one, so with
   one more batch waiting, at most 64 rows are read ahead of the reader. That
   keeps a query that stops early from asking the server for another
   fetch-size batch."
  32)

(defn- put-rows!
  "Puts a batch of rows on out, and returns false if out is closed. On the
   JVM it blocks the reader thread until out takes it, which pushes back on
   the server. In JS it can't block, so it pauses the socket until out takes
   every batch put."
  [ms pending out rows]
  #?(:clj  (async/>!! out rows)
     :cljs (let [state (volatile! :putting)
                 open? (async/put! out rows (fn [_]
                                              (if (= :putting @state)
                                                (vreset! state :taken)
                                                (when (zero? (vswap! pending dec))
                                                  (some-> ms ms/resume)))))]
             (when (= :putting @state)
               (vreset! state :waiting)
               (when (= 1 (vswap! pending inc))
                 (some-> ms ms/pause)))
             open?)))

;; fired turns true once the query's cancel channel has fired; after that the
;; sink puts nothing more, so ending it never waits for a reader who has left.
(deftype ChanSink [out pending fired ^:unsynchronized-mutable rows]
  clunk/Sink
  (-row! [this ms row]
    (set! rows (conj! rows row))
    (if (< (count rows) batch-size) true (clunk/-flush! this ms)))
  (-flush! [_ ms]
    (cond @fired               false
          (zero? (count rows)) (not (async-impl/closed? out))
          :else                (let [batch (persistent! rows)]
                                 (set! rows (transient []))
                                 (put-rows! ms pending out batch))))
  (-error! [this ms err]
    (and (clunk/-flush! this ms) (put-rows! ms pending out [err])))
  (-end! [this ms]
    (clunk/-flush! this ms)
    (async/close! out)))

(defn query
  "Queues a query on a client or a pool and returns a channel of row maps,
   keyed by column name, that closes after the last row. A failed query puts
   an error on the channel. sql is a string or a vector of a statement and
   its parameters, as for clunk.core/query.

   To stop early, pass a :cancel channel, such as a promise-chan or a timeout,
   and close it or put on it: the returned channel closes and the client
   discards the rest of the result. A query still waiting for a pool's
   connection doesn't run at all. Closing the returned channel alone can
   leave the client waiting to put a row, holding up every later query.

   With :cancel, the server sends the rows in batches of :fetch-size (1000
   unless given), so cancelling also stops the server after the current
   batch. Such a query, like one with parameters, is one statement."
  ([client sql] (query client sql nil))
  ([client sql {:keys [cancel fetch-size]}]
   (let [out   (async/chan 1 cat) ;; takes batches of rows; see batch-size
         fired (volatile! false)
         ;; Fired once cancel is closed or holds a value. Taking that value
         ;; means the watcher below never sees it, so it marks fired itself.
         token (when cancel
                 (reify clunk/Cancel
                   (cancelled? [_]
                     (or @fired
                         (and (or (async-impl/closed? cancel) (some? (async/poll! cancel)))
                              (vreset! fired true))))))]
     (when cancel
       ;; A put already parked on out outlives close!, so take until out is
       ;; empty; the next put is refused and the client stops sending rows.
       (async/go
         (async/<! cancel)
         (vreset! fired true)
         (async/close! out)
         (loop [] (when (async/<! out) (recur)))))
     (clunk/query client sql (ChanSink. out (volatile! 0) fired (transient []))
                  {:cancel token :fetch-size fetch-size})
     out)))

(defmacro <query!
  "Inside a go block, parks until the query finishes and returns a vector of
   all its rows (and any error)."
  [client sql]
  (if (:ns &env)
    `(cljs.core.async/<! (cljs.core.async/into [] (query ~client ~sql)))
    `(clojure.core.async/<! (clojure.core.async/into [] (query ~client ~sql)))))

(deftype ChanListener [ch buf max-queued]
  clunk/Listener
  (-notify! [_ n]
    (cond (async-impl/closed? ch)     false
          (<= max-queued (count buf)) :full
          :else                       (do (async/offer! ch n) true)))
  ;; The extra slot in ch's buffer always has room for err.
  (-stop! [_ err]
    (when err (async/offer! ch err))
    (async/close! ch)))

(defn listen
  "Listens for notifications on channel-name, as clunk.core/listen does, and
   returns a channel of them. The connection never waits for it: a channel
   with :max-queued (default 1000) notifications not yet taken gets an error
   value instead and closes. If LISTEN fails, or the connection drops, it
   gets an error value and closes; clunk/close closes it with no error. To
   stop, call clunk.core/unlisten with the channel; closing it yourself
   stops it too, as the next notification on channel-name arrives."
  ([client channel-name] (listen client channel-name nil))
  ([client channel-name {:keys [max-queued] :or {max-queued 1000}}]
   (let [buf (async/buffer (inc max-queued)) ;; the last slot is for an error
         ch  (async/chan buf)]
     (clunk/add-listener! client channel-name ch (ChanListener. ch buf max-queued)))))
