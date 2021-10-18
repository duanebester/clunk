(ns com.clunk.oid-types
  (:require [clojure.core.match :as m]
            [helins.binf :as binf])
  #?(:clj (:import
           (java.nio ByteBuffer))))

(defn oid-converter
  [^Integer oid ^bytes data ^Integer len]
  (let [bb #?(:clj (ByteBuffer/wrap data)
              :cljs (js/Uint8Array. data))
        _ (binf/endian-set bb :big-endian)]
    (m/match oid
      16   (binf/rr-bool bb)
      23   #?(:clj (Integer/parseInt (binf/rr-string bb len))
              :cljs (js/parseInt (binf/rr-string bb len)))
      700  (binf/rr-f32 bb)
      1043 (binf/rr-string bb len)
      1700 (binf/rr-f64 bb)
      :else data)))
