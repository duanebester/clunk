(ns clunk.oid-types
  (:require [clunk.vendor.binf :as binf]))

(defn oid-converter
  "Decodes one column value. Simple queries always return values in text
   format, so each type parses its text. Unknown OIDs return the raw bytes."
  [oid data len]
  (let [s (binf/rr-string (binf/view data) len)]
    (case (int oid)
      16   (= s "t")
      23   #?(:clj (Integer/parseInt s) :cljs (js/parseInt s 10))
      700  #?(:clj (Float/parseFloat s) :cljs (js/parseFloat s))
      1043 s
      ;; numeric is arbitrary precision; JS has no type that holds it exactly.
      1700 #?(:clj (bigdec s) :cljs s)
      data)))
