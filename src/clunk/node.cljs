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

(defn- sql-params
  "sql with the values in a params array, null or undefined for none."
  [sql params]
  (if params (into [sql] params) sql))

(defn query
  "query(client, sql, params?) resolves to an array of row objects. params
   is an array of the values of $1, $2, ... in sql."
  ([client sql] (query client sql nil))
  ([client sql params]
   (.then (clunk/execute client (sql-params sql params))
          clj->js
          #(throw (js-error %)))))

(defn stream
  "stream(client, sql, params?) is an async iterable of row objects, for
   for-await and Readable.from. The server sends rows in batches, so leaving
   early (break, or a destroyed stream) stops it and lets the next query run.
   A failed query rejects."
  ([client sql] (stream client sql nil))
  ([client sql params]
   (let [cancel (async/promise-chan)
         ch     (clunk/query client (sql-params sql params) {:cancel cancel})
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
     it)))

(defn close [client]
  (clunk/close client)
  nil)
