(ns clunk.messages
  (:require [clojure.core.match :as m]
            [helins.binf :as binf]
            [helins.binf.buffer :as binf.buffer]
            [clunk.codecs :as codecs]))

(defn handle-backend-key-data [bs]
  (let [id (binf/rr-i32 bs)
        key (binf/rr-i32 bs)]
    {:type :BackendKeyData
     :id   id
     :key  key}))

(defn handle-ready-for-query [bs]
  (let [status (binf/rr-i8 bs)]
    {:type   :ReadyForQuery
     :status status}))

(defn handle-parameter-status [bs]
  (let [param (codecs/rr-cstring bs)
        status (codecs/rr-cstring bs)]
    {:type   :ParameterStatus
     :param param :status status}))

(defn print-ints
  "Prints byte array as ints"
  [ba]
  (println (map (partial int) ba)))

(defn handle-data-row [bs]
  (let [cols (binf/rr-i16 bs)]
    (loop [i      0
           arr    []]
      (if (<= cols i)
        {:type     :DataRow
         :num-cols cols
         :values   arr}
        (let [len  (binf/rr-i32 bs)
              data (binf/rr-buffer bs len)]
          (recur (inc i) (conj arr {:idx  i
                                    :data data
                                    :length len})))))))

(defn handle-row-description [bs]
  (let [cols  (binf/rr-i16 bs)]
    (if (< 0 cols)
      (loop [i 0 items []]
        (if (<= cols i)
          {:type     :RowDescription
           :num-cols cols
           :values   items}
          (recur (inc i) (conj items (codecs/rr-row-desc bs)))))
      {:type     :RowDescription
       :num-cols cols
       :values   nil})))

(defn handle-authentication [bs]
  (let [auth-req (codecs/rr-auth-req bs)]
    (m/match auth-req
      {:tag 0}
      {:type :AuthenticationOk}
      {:tag 5}
      {:type :AuthenticationMD5
       :salt (codecs/rr-auth-salt bs)}
      {:tag 10}
      {:type :AuthenticationClearText})))

(defn handle-error
  ([bs] (handle-error bs []))
  ([bs errs]
   (let [head (binf/rr-i8 bs)]
     (if (< 0 head)
       (let [msg (codecs/rr-cstring bs)]
         (handle-error bs (conj errs msg)))
       {:type   :ErrorMessage
        :errors errs}))))

(defn handle-close [bs]
  (let [close (binf/rr-i8 bs)
        name (codecs/rr-cstring bs)]
    {:type :Close :close close :name name}))

(defn handle-unknown [bs header]
  (let [unk (binf/rr-buffer bs (- (:len header) 4))]
    {:type    :Unknown
     :header  header
     :payload #?(:clj unk :cljs (js/Uint8Array. unk))}))

(defn build-query
  [^String query]
  (let [len   (+ (count query) 4 1)
        bb (binf/view (binf.buffer/alloc (+ 1 len)))]
    (-> bb
        (binf/wr-b8 (byte 0x51))
        (binf/wr-b32 (int len))
        (codecs/wr-cstring query))
    bb))

(defn build-password
  [^String password]
  (let [len   (+ (count password) 4 1)
        view (binf/view (binf.buffer/alloc (+ 1 len)))]
    (-> view
        (binf/wr-b8 (byte 0x70))
        (binf/wr-b32 (int len))
        (codecs/wr-cstring password))
    view))

(defn build-startup
  [^String user ^String database]
  (let [len   (+ (count user) (count database) 16 4 4 1)
        view (binf/view (binf.buffer/alloc len))]
    (-> view
        (binf/wr-b32 (int len))
        (binf/wr-b32 (int 196608))
        (codecs/wr-cstring "user")
        (codecs/wr-cstring user)
        (codecs/wr-cstring "database")
        (codecs/wr-cstring database)
        (binf/wr-b8 (byte 0x00)))
    view))

(comment
  (defn print-ints
    "Prints byte array as ints"
    [ba]
    (println (map (partial int) ba)))

  (defn rr-build-query
    [view]
    [(binf/rr-i8 view)
     (binf/rr-i32 view)
     (codecs/rr-cstring view)])

  (build-query "SEE")
  (print-ints (binf/backing-buffer (build-query "SELECT 1;")))

  (let [view (build-query "SELECT 1;")]
    (-> view
        (binf/seek 0)
        rr-build-query))

  (print-ints (binf/backing-buffer (build-password "TEST")))
  (print-ints (binf/backing-buffer (build-query "TEST")))
  (print-ints (binf/backing-buffer (build-startup "USER" "PASS"))))
