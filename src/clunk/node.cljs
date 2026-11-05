(ns clunk.node
  "Promise API for JavaScript callers, exported by the shadow-cljs :node build."
  (:require [cljs.core.async :as async]
            [clunk.core :as clunk]))

(defn- js-error
  "A plain JS Error for a clunk error, with the Postgres fields (code,
   severity) and the sql as properties, as node-postgres has them."
  [e]
  (let [err (js/Error. (ex-message e))]
    (doseq [[k v] (select-keys (ex-data e) [:code :severity :sql])]
      (aset err (name k) v))
    err))

(defn connect
  "connect({hostname, port, username, password, database}) resolves to a
   client once the server is ready for queries."
  [config]
  (let [c (clunk/client (js->clj config :keywordize-keys true))]
    (js/Promise.
     (fn [resolve reject]
       (async/take! (clunk/connect c)
                    (fn [result]
                      (if (clunk/error? result)
                        (do (clunk/close c) (reject (js-error result)))
                        (resolve c))))))))

(defn query
  "query(client, sql) resolves to an array of row objects."
  [client sql]
  (.then (clunk/execute client sql)
         clj->js
         #(throw (js-error %))))

(defn stream
  "stream(client, sql) is an async iterable of row objects, for for-await and
   Readable.from. Leaving early (break, or a destroyed stream) lets the next
   query run. A failed query rejects."
  [client sql]
  (let [cancel (async/promise-chan)
        ch     (clunk/query client sql {:cancel cancel})
        stop   #(async/close! cancel)
        it     #js {:next   (fn []
                              (js/Promise.
                               (fn [resolve reject]
                                 (async/take! ch (fn [row]
                                                   (cond
                                                     (nil? row)         (resolve #js {:done true})
                                                     (clunk/error? row) (do (stop) (reject (js-error row)))
                                                     :else              (resolve #js {:done false :value (clj->js row)})))))))
                    :return (fn []
                              (stop)
                              (js/Promise.resolve #js {:done true}))}]
    (aset it js/Symbol.asyncIterator (fn [] it))
    it))

(defn close [client]
  (clunk/close client)
  nil)
