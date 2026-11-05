(ns clunk.messages
  (:require [clunk.vendor.binf :as binf]
            [clunk.vendor.binf.buffer :as binf.buffer]
            [clunk.vendor.binf.string :as binf.string]
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

(defn handle-command-complete [bs]
  {:type :CommandComplete
   :tag  (codecs/rr-cstring bs)})

(defn handle-unknown [bs header]
  (let [unk (binf/rr-buffer bs (- (:len header) 4))]
    {:type    :Unknown
     :header  header
     :payload #?(:clj unk :cljs (js/Uint8Array. unk))}))

(defn build-query
  [^String query]
  (let [q    (binf.string/encode query)
        len  (+ 4 (count q) 1)
        view (binf/view (binf.buffer/alloc (+ 1 len)))]
    (-> view
        (binf/wr-b8 (byte 0x51))
        (binf/wr-b32 (int len))
        (codecs/wr-cstring q))
    view))

(defn build-password
  [^String password]
  (let [pw   (binf.string/encode password)
        len  (+ 4 (count pw) 1)
        view (binf/view (binf.buffer/alloc (+ 1 len)))]
    (-> view
        (binf/wr-b8 (byte 0x70))
        (binf/wr-b32 (int len))
        (codecs/wr-cstring pw))
    view))

(defn build-sasl-initial-response
  [^String mechanism ^String data]
  (let [m    (binf.string/encode mechanism)
        d    (binf.string/encode data)
        len  (+ 4 (count m) 1 4 (count d))
        view (binf/view (binf.buffer/alloc (+ 1 len)))]
    (-> view
        (binf/wr-b8 (byte 0x70))
        (binf/wr-b32 (int len))
        (codecs/wr-cstring m)
        (binf/wr-b32 (int (count d)))
        (binf/wr-buffer d))
    view))

(defn build-sasl-response
  [^String data]
  (let [d    (binf.string/encode data)
        len  (+ 4 (count d))
        view (binf/view (binf.buffer/alloc (+ 1 len)))]
    (-> view
        (binf/wr-b8 (byte 0x70))
        (binf/wr-b32 (int len))
        (binf/wr-buffer d))
    view))

(defn build-startup
  "Asks for UTF8 as the client encoding, since clunk encodes and decodes all
   text as UTF-8. The server converts to and from the database's encoding, and
   rejects text that encoding can't hold."
  [^String user ^String database]
  (let [params (map binf.string/encode ["user" user "database" database
                                        "client_encoding" "UTF8"])
        len    (reduce + (+ 4 4 1) (map #(inc (count %)) params))
        view   (binf/view (binf.buffer/alloc len))]
    (-> view
        (binf/wr-b32 (int len))
        (binf/wr-b32 (int 196608)))
    (doseq [p params]
      (codecs/wr-cstring view p))
    (binf/wr-b8 view (byte 0x00))
    view))

;; The extended query protocol. clunk uses only the unnamed statement and the
;; unnamed portal, both written as the empty string, and text format for
;; parameters and results.

(defn- frontend-message
  "A message of tag, its int32 length, and body-size bytes that write puts on
   the view."
  [tag body-size write]
  (let [view (binf/view (binf.buffer/alloc (+ 5 body-size)))]
    (binf/wr-b8 view tag)
    (binf/wr-b32 view (+ 4 body-size))
    (write view)
    view))

(defn build-parse
  "Parses sql into the unnamed statement, leaving the server to infer every
   parameter's type."
  [^String sql]
  (let [q (binf.string/encode sql)]
    (frontend-message 0x50 (+ 1 (count q) 1 2)
                      #(-> %
                           (binf/wr-b8 0)
                           (binf/wr-buffer q)
                           (binf/wr-b8 0)
                           (binf/wr-b16 0)))))

(defn build-bind
  "Binds params, each a string or nil for NULL, to the unnamed portal."
  [params]
  (let [values (mapv #(some-> % binf.string/encode) params)]
    (frontend-message 0x42 (reduce + (+ 1 1 2 2 2) (map #(+ 4 (count %)) values))
                      (fn [view]
                        (-> view
                            (binf/wr-b8 0)
                            (binf/wr-b8 0)
                            (binf/wr-b16 0) ;; every parameter in text format
                            (binf/wr-b16 (count values)))
                        (doseq [v values]
                          (if v
                            (-> view (binf/wr-b32 (count v)) (binf/wr-buffer v))
                            (binf/wr-b32 view -1)))
                        (binf/wr-b16 view 0))))) ;; every result column in text format

(defn build-describe-portal []
  (frontend-message 0x44 2 #(-> % (binf/wr-b8 0x50) (binf/wr-b8 0))))

(defn build-execute
  "Runs the unnamed portal for up to max-rows rows, or all of them for 0."
  [max-rows]
  (frontend-message 0x45 5 #(-> % (binf/wr-b8 0) (binf/wr-b32 max-rows))))

(defn build-close-portal []
  (frontend-message 0x43 2 #(-> % (binf/wr-b8 0x50) (binf/wr-b8 0))))

(defn build-flush []
  (frontend-message 0x48 0 identity))

(defn build-sync []
  (frontend-message 0x53 0 identity))

(defn build-batch
  "Built messages copied into one buffer, so they go out in one write."
  [views]
  (let [buffers (map binf/backing-buffer views)
        view    (binf/view (binf.buffer/alloc (reduce + (map count buffers))))]
    (doseq [b buffers]
      (binf/wr-buffer view b))
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
