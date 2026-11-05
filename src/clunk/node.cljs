(ns clunk.node
  "Promise API for JavaScript callers, exported by the shadow-cljs :node build."
  (:require [cljs.core.async :as async]
            [clunk.core :as clunk]))

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
                        (do (clunk/close c) (reject result))
                        (resolve c))))))))

(defn query
  "query(client, sql) resolves to an array of row objects."
  [client sql]
  (js/Promise.
   (fn [resolve reject]
     (async/take! (async/into [] (clunk/query client sql))
                  (fn [rows]
                    (if-let [err (first (filter clunk/error? rows))]
                      (reject err)
                      (resolve (clj->js rows))))))))

(defn close [client]
  (clunk/close client)
  nil)
