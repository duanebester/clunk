(ns clunk.pool
  "A pool of clients, so queries run in parallel on up to :max-size
   connections. query, execute and plan take a pool wherever they take a
   client."
  (:require [clojure.core.async :as async]
            [clunk.core :as clunk]
            [clunk.message-socket :as ms]))

;; The pool's state lives in one atom, and every change to it is a swap that
;; decides what to do from the state it replaced, so it's safe across JVM
;; threads and needs no channels:
;;   :idle     connected clients, most recently used first
;;   :size     clients open or opening, idle or lent
;;   :waiters  queue of lend functions waiting for a client
;;   :closed?  true once close is called

(defn- closed-error []
  (ex-info "Pool is closed" {}))

(defn- connected? [client]
  (ms/connected? (:message-socket client)))

(defn- new-client
  "A channel that yields a client, or an error. The JVM's client connects its
   socket as it's made, which blocks, so that happens on its own thread."
  [config]
  #?(:clj  (async/thread
             (try (clunk/client config)
                  (catch Exception e
                    (ex-info (str "Could not connect: " (ex-message e)) {} e))))
     :cljs (async/to-chan! [(clunk/client config)])))

(declare free-slot!)

(defn- open!
  "Opens a client in a slot already counted in :size and calls lend with it,
   or with an error after giving the slot up."
  [{:keys [config state] :as pool} lend]
  (async/go
    (let [c     (async/<! (new-client config))
          ready (if (clunk/error? c) c (async/<! (clunk/connect c)))
          err   (cond (clunk/error? ready) ready
                      (:closed? @state)    (closed-error))]
      (if err
        (do (when-not (clunk/error? c) (clunk/close c))
            (free-slot! pool)
            (lend err))
        (lend c)))))

(defn- free-slot!
  "Gives up a slot whose client is gone: the next waiter gets a new client in
   it, or the pool shrinks."
  [{:keys [state] :as pool}]
  (let [[{:keys [waiters]}] (swap-vals! state (fn [{:keys [waiters] :as s}]
                                                (if (seq waiters)
                                                  (update s :waiters pop)
                                                  (update s :size dec))))]
    (when-let [lend (peek waiters)]
      (open! pool lend))))

(defn- checkout!
  "Calls lend with a client, now if one is idle or the pool can grow, else
   once one is checked in. Calls it with an error if connecting fails or the
   pool is closed."
  [{:keys [state max-size] :as pool} lend]
  (let [[{:keys [closed? idle size]}]
        (swap-vals! state (fn [{:keys [closed? idle size] :as s}]
                            (cond closed?           s
                                  (seq idle)        (update s :idle pop)
                                  (< size max-size) (update s :size inc)
                                  :else             (update s :waiters conj lend))))]
    (cond closed?           (lend (closed-error))
          (seq idle)        (let [c (peek idle)]
                              ;; The server may have hung up while it sat idle;
                              ;; if so, a new client takes its slot.
                              (if (connected? c)
                                (lend c)
                                (do (clunk/close c)
                                    (open! pool lend))))
          (< size max-size) (open! pool lend))))

(defn- checkin!
  "Takes back a client whose query has finished: it goes to the next waiter,
   or back to :idle. A client that has lost its connection is closed instead."
  [{:keys [state] :as pool} c]
  (if (connected? c)
    (let [[{:keys [closed? waiters]}]
          (swap-vals! state (fn [{:keys [closed? waiters] :as s}]
                              (cond closed?       s
                                    (seq waiters) (update s :waiters pop)
                                    :else         (update s :idle conj c))))]
      (cond closed?       (do (clunk/close c) (free-slot! pool))
            (seq waiters) ((peek waiters) c)))
    (do (clunk/close c)
        (free-slot! pool))))

(defrecord Pool [config max-size state]
  clunk/Queryable
  (submit [this {:keys [out cancel cancelled] :as request}]
    (checkout! this (fn [c]
                      (if (clunk/error? c)
                        (async/put! out [c] (fn [_] (async/close! out)))
                        ;; A go block, so a long run of cancelled waiters, each
                        ;; handing the client to the next, stays off the stack.
                        (async/go
                          ;; A query cancelled while it waited must not run.
                          ;; query's watcher sets cancelled only once the
                          ;; dispatch pool resumes it, which can be after this,
                          ;; so look at cancel itself too: closed, or holding a
                          ;; value. Taking that value means the watcher never
                          ;; fires, so close out here.
                          (if (or @cancelled
                                  (and cancel (not= :default (second (async/alts! [cancel] :default nil)))))
                            (do (async/close! out)
                                (checkin! this c))
                            (clunk/submit c (assoc request :done #(checkin! this c)))))))))
  (close [this]
    (let [[{:keys [idle waiters]}]
          (swap-vals! state (fn [{:keys [idle waiters] :as s}]
                              (-> s
                                  (assoc :closed? true :idle () :waiters (empty waiters))
                                  (update :size - (count idle)))))]
      (run! clunk/close idle)
      (run! #(% (closed-error)) waiters)
      this)))

(defn pool
  "A pool of up to :max-size (default 10) clients, each made from config as
   clunk.core/client makes one. It connects a client only when a query needs
   one and none is idle, so making a pool doesn't touch the server. A query
   holds its client until its rows are all read or it is cancelled; queries
   beyond :max-size wait their turn. A client whose connection was lost is
   replaced. clunk.core/close closes the idle clients at once, and the busy
   ones as their queries finish."
  [config]
  (map->Pool {:config   config
              :max-size (get config :max-size 10)
              :state    (atom {:idle    ()
                               :size    0
                               :waiters #?(:clj  clojure.lang.PersistentQueue/EMPTY
                                           :cljs cljs.core/PersistentQueue.EMPTY)
                               :closed? false})}))
