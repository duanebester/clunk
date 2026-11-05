(ns clunk.oid-types
  (:require [clunk.vendor.binf :as binf]))

(defn decoder
  "Returns a function of (view n-byte) that reads one column value of the given
   type from the view's current position. Simple queries always return values
   in text format, so each type parses its text. Unknown OIDs return the raw
   bytes."
  [oid]
  (case (int oid)
    16   (fn [view n] (= "t" (binf/rr-string view n)))
    23   (fn [view n] #?(:clj (Integer/parseInt (binf/rr-string view n))
                         :cljs (js/parseInt (binf/rr-string view n) 10)))
    700  (fn [view n] #?(:clj (Float/parseFloat (binf/rr-string view n))
                         :cljs (js/parseFloat (binf/rr-string view n))))
    1043 binf/rr-string
    ;; numeric is arbitrary precision; JS has no type that holds it exactly.
    1700 (fn [view n] #?(:clj (bigdec (binf/rr-string view n))
                         :cljs (binf/rr-string view n)))
    binf/rr-buffer))
