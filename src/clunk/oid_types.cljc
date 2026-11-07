(ns clunk.oid-types
  #?(:clj (:import (java.nio.charset StandardCharsets)
                   (java.time LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZoneOffset)
                   (java.util Arrays))))

;; Dates and times arrive in Postgres's ISO text format (DateStyle ISO, the
;; default), such as "2026-11-05 07:45:01.5-06". Years have at least four
;; digits, BC years end in " BC", and offsets can carry seconds ("-05:50:36").

(defn- char-digit [^String s i]
  #?(:clj (- (int (.charAt s (int i))) 48)
     :cljs (- (.charCodeAt s i) 48)))

(defn- digits
  "The number written in s's decimal digits from index i up to j."
  ^long [s i j]
  (loop [i i n 0]
    (if (< i j)
      (recur (inc i) (+ (* 10 n) (char-digit s i)))
      n)))

(defn- parts []
  #?(:clj (long-array 8) :cljs (array 0 0 0 0 0 0 0 0)))

(defn- time-parts
  "Reads HH:MM:SS[.ffffff][±HH[:MM[:SS]]] from s between i and end into p's
   hour, minute, second, nanosecond and offset-second slots (3 to 7)."
  [^String s i end ^longs ps]
  (aset ps 3 (digits s i (+ i 2)))
  (aset ps 4 (digits s (+ i 3) (+ i 5)))
  (aset ps 5 (digits s (+ i 6) (+ i 8)))
  (let [j (+ i 8)
        k (if (and (< j end) (= \. (.charAt s j)))
            (loop [k (inc j)]
              (if (and (< k end) (<= 0 (char-digit s k) 9)) (recur (inc k)) k))
            j)]
    (when (< j k)
      (aset ps 6 (long (loop [v (digits s (inc j) k) d (- k j 1)]
                        (if (< d 9) (recur (* 10 v) (inc d)) v)))))
    (when (< k end)
      (let [h (digits s (+ k 1) (+ k 3))
            m (if (< (+ k 3) end) (digits s (+ k 4) (+ k 6)) 0)
            x (if (< (+ k 6) end) (digits s (+ k 7) (+ k 9)) 0)]
        (aset ps 7 (* (if (= \- (.charAt s k)) -1 1) (+ (* 3600 h) (* 60 m) x)))))
    ps))

(defn- datetime-parts
  "Reads a date, timestamp or timestamptz into an array of year, month, day,
   hour, minute, second, nanosecond and offset-second. Year 1 BC is year 0."
  [^String s]
  (let [n    (count s)
        bc?  (= \B (.charAt s (- n 2)))
        end  (if bc? (- n 3) n)
        dash (.indexOf s "-")
        year (digits s 0 dash)
        ^longs ps (parts)]
    (aset ps 0 (if bc? (- 1 year) year))
    (aset ps 1 (digits s (+ dash 1) (+ dash 3)))
    (aset ps 2 (digits s (+ dash 4) (+ dash 6)))
    (when (< (+ dash 6) end)
      (time-parts s (+ dash 7) end ps))
    ps))

;; ---- reading a value's bytes
;;
;; A decoder reads one value from b, the bytes of the DataRow it came in: a
;; byte array on the JVM and a Node Buffer in JS, the n bytes from index off.

(defn- byte-at
  "The byte at index i of b. Only ever compared with ASCII, so a JVM byte's
   sign doesn't matter."
  ^long [b ^long i]
  #?(:clj (long (aget ^bytes b i)) :cljs (aget b i)))

(defn text
  "The value's bytes as UTF-8 text. In JS, Buffer's toString decodes them in
   place, at about half the cost of a TextDecoder and the Uint8Array it needs.
   Like the JVM, it keeps a leading byte order mark, which TextDecoder drops."
  ^String [b off n]
  #?(:clj  (String. ^bytes b (int off) (int n) StandardCharsets/UTF_8)
     :cljs (.toString b "utf8" off (+ off n))))

(defn- raw
  "A copy of the value's bytes: a byte array on the JVM, an ArrayBuffer in JS."
  [b off n]
  #?(:clj  (Arrays/copyOfRange ^bytes b (int off) (int (+ off n)))
     :cljs (let [start (+ (.-byteOffset b) off)]
             (.slice (.-buffer b) start (+ start n)))))

(defn- integer
  "The integer written in the value's bytes: an optional minus sign, then
   digits, as Postgres writes int2, int4, int8 and oid. It counts down from
   0, so the most negative int8 doesn't overflow on the way, and (- 0 v)
   keeps JS from returning -0 for 0."
  ^long [b ^long off ^long n]
  (let [end  (+ off n)
        minus? (== 45 (byte-at b off))]
    (loop [i (if minus? (inc off) off)
           v 0]
      (if (< i end)
        (recur (inc i) (- (* 10 v) (- (byte-at b i) 48)))
        (if minus? v (- 0 v))))))

(def ^:private powers-of-ten
  "10^0 to 10^15, each exact as a double."
  #?(:clj  (double-array (take 16 (iterate #(* 10.0 %) 1.0)))
     :cljs (into-array (take 16 (iterate #(* 10 %) 1)))))

(defn- float8
  "The double written in the value's bytes. Most values, digits with an
   optional point and at most 15 digits in all, are read from the bytes: the
   digits as a whole number and the power of ten are both exact doubles, so
   one division rounds as parsing the text does. Anything else (more digits,
   an exponent, NaN, Infinity) is parsed as text."
  [b ^long off ^long n]
  (let [end  (+ off n)
        minus? (== 45 (byte-at b off))]
    (loop [i      (if minus? (inc off) off)
           m      0
           point  -1
           digits 0]
      (if (< i end)
        (let [c (byte-at b i)]
          (cond (and (<= 48 c) (<= c 57) (< digits 15)) (recur (inc i) (+ (* 10 m) (- c 48)) point (inc digits))
                (and (== 46 c) (neg? point))            (recur (inc i) m i digits)
                :else                                   (recur end 0 -1 16)))
        (if (and (<= 1 digits) (<= digits 15))
          (let [x (if (neg? point)
                    (double m)
                    (/ (double m) (aget #?(:clj ^doubles powers-of-ten :cljs powers-of-ten) (- end point 1))))]
            (if minus? (- x) x))
          #?(:clj  (Double/parseDouble (text b off n))
             :cljs (js/parseFloat (.toString b "latin1" off end))))))))

;; ----

(defn- infinite
  "Reads a value that may be infinity or -infinity, which no date type holds:
   those return pos or neg, and anything else goes through parse."
  [pos neg parse]
  (fn [b off n]
    (let [s (text b off n)]
      (case (.charAt ^String s 0)
        \i pos
        \- neg
        (parse (datetime-parts s))))))

(defn- part ^long [ps i]
  #?(:clj (aget ^longs ps i) :cljs (aget ps i)))

#?(:clj
   (defn- local-time [ps]
     ;; Postgres allows 24:00:00, one past the last LocalTime.
     (if (= 24 (part ps 3))
       LocalTime/MAX
       (LocalTime/of (part ps 3) (part ps 4) (part ps 5) (part ps 6)))))

#?(:cljs
   (defn- js-date
     "A JS Date from parts, read in local time or, with utc?, as UTC shifted by
      the offset. setFullYear rather than the constructor, which reads years
      0 to 99 as 1900 to 1999. Microseconds don't fit and are dropped."
     [ps utc?]
     (let [d  (js/Date. 0)
           ms (js/Math.floor (/ (part ps 6) 1e6))]
       (if utc?
         (do (.setUTCFullYear d (part ps 0) (dec (part ps 1)) (part ps 2))
             (.setUTCHours d (part ps 3) (part ps 4) (part ps 5) ms)
             (.setTime d (- (.getTime d) (* 1000 (part ps 7)))))
         (do (.setFullYear d (part ps 0) (dec (part ps 1)) (part ps 2))
             (.setHours d (part ps 3) (part ps 4) (part ps 5) ms)))
       d)))

(defn- hex-value [c]
  ;; '0'-'9' are 0x30-0x39, 'a'-'f' 0x61-0x66 and 'A'-'F' 0x41-0x46.
  (+ (bit-and c 15) (* 9 (bit-shift-right c 6))))

(defn- bytea
  "Decodes bytea's hex text, \\x then two digits per byte, straight from its
   bytes. The older escape format, used only when the server sets
   bytea_output to escape, comes back as its raw text bytes."
  [b off n]
  (if (and (<= 2 n) (== 120 (byte-at b (inc off)))) ;; \x
    (let [len (quot (- n 2) 2)]
      #?(:clj (let [^bytes src b
                    out        (byte-array len)]
                (loop [i 0 j (+ off 2)]
                  (when (< i len)
                    (aset out i (unchecked-byte (bit-or (bit-shift-left (hex-value (aget src j)) 4)
                                                        (hex-value (aget src (inc j))))))
                    (recur (inc i) (+ j 2))))
                out)
         :cljs (let [out (js/Uint8Array. len)]
                 (loop [i 0 j (+ off 2)]
                   (when (< i len)
                     (aset out i (bit-or (bit-shift-left (hex-value (aget b j)) 4)
                                         (hex-value (aget b (inc j)))))
                     (recur (inc i) (+ j 2))))
                 (.-buffer out))))
    (raw b off n)))

(defn decoder
  "Returns a function of (b off n) that reads one column value of the given
   type from n bytes of b, its DataRow's bytes, starting at index off: b is a
   byte array on the JVM and a Node Buffer in JS. Simple queries always
   return values in text format, so each type parses its text. Unknown OIDs
   return the raw bytes."
  [oid]
  (case (int oid)
    16                       (fn [b off _] (== 116 (byte-at b off))) ;; t
    17                       bytea
    ;; int2 and int4 both read as an int, as pgJDBC does.
    (21 23)                  #?(:clj (fn [b off n] (int (integer b off n)))
                                :cljs integer)
    ;; int8 overflows a JS number past 2^53, so Node gets a string, as
    ;; node-postgres does.
    20                       #?(:clj integer :cljs text)
    ;; oid is unsigned, so it can overflow an int.
    26                       integer
    700                      #?(:clj (fn [b off n] (Float/parseFloat (text b off n)))
                                :cljs float8)
    701                      float8
    ;; char(n) keeps the server's trailing-space padding, as pgJDBC and
    ;; node-postgres do. name is a catalog identifier.
    (19 25 114 1042 1043 2950 3802)  text
    ;; Infinite dates are MAX and MIN on the JVM, as in pgJDBC, and
    ;; Infinity and -Infinity on Node, as in node-postgres.
    1082                     #?(:clj (infinite LocalDate/MAX LocalDate/MIN
                                               #(LocalDate/of (part % 0) (part % 1) (part % 2)))
                                :cljs (infinite js/Infinity (- js/Infinity) #(js-date % false)))
    1114                     #?(:clj (infinite LocalDateTime/MAX LocalDateTime/MIN
                                               #(LocalDateTime/of (LocalDate/of (part % 0) (part % 1) (part % 2))
                                                                  (local-time %)))
                                :cljs (infinite js/Infinity (- js/Infinity) #(js-date % false)))
    1184                     #?(:clj (infinite OffsetDateTime/MAX OffsetDateTime/MIN
                                               #(OffsetDateTime/of (part % 0) (part % 1) (part % 2) (part % 3) (part % 4) (part % 5) (part % 6)
                                                                   (ZoneOffset/ofTotalSeconds (part % 7))))
                                :cljs (infinite js/Infinity (- js/Infinity) #(js-date % true)))
    ;; JS has no time-of-day type, so Node gets time and timetz as strings,
    ;; as node-postgres does.
    1083                     #?(:clj (fn [b off n] (local-time (time-parts (text b off n) 0 n (parts))))
                                :cljs text)
    1266                     #?(:clj (fn [b off n]
                                       (let [ps (time-parts (text b off n) 0 n (parts))]
                                         (OffsetTime/of (local-time ps) (ZoneOffset/ofTotalSeconds (part ps 7)))))
                                :cljs text)
    ;; numeric is arbitrary precision; JS has no type that holds it exactly.
    1700                     #?(:clj (fn [b off n] (BigDecimal. (text b off n)))
                                :cljs text)
    raw))

#?(:cljs
   (defn- local-timestamp
     "A Date as local time with its offset, as node-postgres sends it, so a
      timestamp column stores the local time it reads back."
     [^js d]
     (let [pad    #(.padStart (str %1) %2 "0")
           offset (- (.getTimezoneOffset d))]
       (str (pad (.getFullYear d) 4) "-" (pad (inc (.getMonth d)) 2) "-" (pad (.getDate d) 2)
            "T" (pad (.getHours d) 2) ":" (pad (.getMinutes d) 2) ":" (pad (.getSeconds d) 2)
            "." (pad (.getMilliseconds d) 3)
            (if (neg? offset) "-" "+") (pad (quot (js/Math.abs offset) 60) 2) ":" (pad (mod (js/Math.abs offset) 60) 2)))))

(defn param-text
  "The text Postgres reads for a query parameter, or nil for NULL. Strings,
   numbers, booleans and UUIDs go as written, bytes as bytea hex, and dates
   and times in ISO format. Postgres infers each parameter's type from the
   query, so a parameter whose type the query doesn't fix, as in SELECT $1,
   needs a cast such as $1::int4."
  [v]
  (cond
    (nil? v)    nil
    (string? v) v
    #?@(:clj  [(bytes? v)
               (let [sb (StringBuilder. "\\x")]
                 (doseq [b ^bytes v]
                   (.append sb (Character/forDigit (bit-and (bit-shift-right b 4) 15) 16))
                   (.append sb (Character/forDigit (bit-and b 15) 16)))
                 (str sb))
               (decimal? v)                   (.toPlainString ^BigDecimal v)
               (instance? java.util.Date v)   (str (.toInstant ^java.util.Date v))]
        :cljs [(instance? js/Date v)          (local-timestamp v)
               (or (instance? js/ArrayBuffer v) (instance? js/Uint8Array v))
               (str "\\x" (.toString (js/Buffer.from v) "hex"))])
    :else       (str v)))
