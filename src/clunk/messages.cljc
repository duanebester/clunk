(ns clunk.messages
  (:require [clunk.vendor.binf :as binf]
            [clunk.vendor.binf.buffer :as binf.buffer]
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

(defn handle-data-row
  "Leaves the columns undecoded: the query that asked for them knows their
   types, and decodes them straight from the view (see clunk.core)."
  [bs]
  {:type :DataRow
   :view bs})

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
    (case (int (:tag auth-req))
      0  {:type :AuthenticationOk}
      5  {:type :AuthenticationMD5
          :salt (codecs/rr-auth-salt bs)}
      10 {:type       :AuthenticationSASL
          :mechanisms (loop [ms []]
                        (let [m (codecs/rr-cstring bs)]
                          (if (empty? m) ms (recur (conj ms m)))))}
      11 {:type :AuthenticationSASLContinue
          :data (binf/rr-string bs (binf/remaining bs))}
      12 {:type :AuthenticationSASLFinal
          :data (binf/rr-string bs (binf/remaining bs))})))

(defn handle-error
  "Each field is a type byte and a string; S is severity, C the SQLSTATE code,
   and M the message."
  ([bs] (handle-error bs [] {}))
  ([bs errs fields]
   (let [head (binf/rr-i8 bs)]
     (if (< 0 head)
       (let [msg (codecs/rr-cstring bs)]
         (recur bs (conj errs msg) (assoc fields (char head) msg)))
       {:type     :ErrorMessage
        :errors   errs
        :severity (get fields \S)
        :code     (get fields \C)
        :message  (get fields \M)}))))

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

(defn build-sasl-initial-response
  [^String mechanism ^String data]
  (let [len  (+ 4 (count mechanism) 1 4 (count data))
        view (binf/view (binf.buffer/alloc (+ 1 len)))]
    (-> view
        (binf/wr-b8 (byte 0x70))
        (binf/wr-b32 (int len))
        (codecs/wr-cstring mechanism)
        (binf/wr-b32 (int (count data)))
        (binf/wr-string data))
    view))

(defn build-sasl-response
  [^String data]
  (let [len  (+ 4 (count data))
        view (binf/view (binf.buffer/alloc (+ 1 len)))]
    (-> view
        (binf/wr-b8 (byte 0x70))
        (binf/wr-b32 (int len))
        (binf/wr-string data))
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
