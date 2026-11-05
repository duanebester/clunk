(ns clunk.oid-types
  (:require [clunk.vendor.binf :as binf]))

(defn decoder
  "Returns a function of (view n-byte) that reads one column value of the given
   type from the view's current position. Simple queries always return values
   in text format, so each type parses its text. Unknown OIDs return the raw
   bytes."
  [oid]
  (case (int oid)
    16             (fn [view n] (= "t" (binf/rr-string view n)))
    ;; int2 and int4 both read as an int, as pgJDBC does.
    (21 23)        (fn [view n] #?(:clj (Integer/parseInt (binf/rr-string view n))
                                   :cljs (js/parseInt (binf/rr-string view n) 10)))
    ;; int8 overflows a JS number past 2^53, so Node gets a string, as
    ;; node-postgres does.
    20             (fn [view n] #?(:clj (Long/parseLong (binf/rr-string view n))
                                   :cljs (binf/rr-string view n)))
    700            (fn [view n] #?(:clj (Float/parseFloat (binf/rr-string view n))
                                   :cljs (js/parseFloat (binf/rr-string view n))))
    701            (fn [view n] #?(:clj (Double/parseDouble (binf/rr-string view n))
                                   :cljs (js/parseFloat (binf/rr-string view n))))
    ;; char(n) keeps the server's trailing-space padding, as pgJDBC and
    ;; node-postgres do.
    (25 1042 1043) binf/rr-string
    ;; numeric is arbitrary precision; JS has no type that holds it exactly.
    1700           (fn [view n] #?(:clj (bigdec (binf/rr-string view n))
                                   :cljs (binf/rr-string view n)))
    binf/rr-buffer))
