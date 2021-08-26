(ns com.clunk.codecs
  (:require [octet.core :as buf]
            [octet.buffer :as obuf]
            [octet.spec :as spec]
            [octet.spec.string :as specstr]))

(def ^{:doc "Arbitrary length cstring (null-terminated string) type spec."}
  cstring
  (reify
    #?@(:clj
        [clojure.lang.IFn
         (invoke [s] s)]
        :cljs
        [cljs.core/IFn
         (-invoke [s] s)])

    spec/ISpecDynamicSize
    (size* [_ data]
      (let [data (specstr/string->bytes data)]
        (inc (count data))))

    spec/ISpec
    (read [_ buff pos]
      (loop [index pos acc []]
        (let [b (obuf/read-byte buff index)]
          (if (zero? b)
            [(inc (count acc)) (specstr/bytes->string (byte-array acc) (count acc))]
            (recur (inc index) (conj acc b))))))

    (write [_ buff pos value]
      (let [input (specstr/string->bytes (str value "\0"))
            length (count input)]
        (obuf/write-bytes buff pos length input)
        length))))


(def header-codec (buf/spec :tag buf/byte :len buf/int32))
(def auth-req-codec (buf/spec :tag buf/int32))
(def row-desc-codec (buf/spec :field-name cstring :table-oid buf/int32 :column-attr buf/int16 :oid buf/int32 :data-type-size buf/int16 :type-modifier buf/int32 :format-code buf/int16))

;; Usage
(comment
  (def spec (buf/spec buf/int32 cstring cstring buf/byte))
  (def data [(int 10) "hay" "hey" (byte 1)])
  (def buffer (buf/allocate 20))
  (buf/write! buffer data spec)
  (buf/read buffer spec))