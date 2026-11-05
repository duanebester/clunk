(ns clunk.codecs
  (:require [clunk.vendor.binf :as binf]))

(defn wr-cstring
  "Writes bs, a string already encoded with binf.string/encode, and the NUL
   that ends it. Builders encode first because Postgres sizes messages in
   UTF-8 bytes, not characters."
  [view bs]
  (-> view
      (binf/wr-buffer bs)
      (binf/wr-b8 0)))

(defn rr-cstring [view]
  (let [pos (binf/position view)]
    (loop []
      (if (zero? (binf/rr-u8 view))
        (binf/ra-string view pos (- (binf/position view) pos 1))
        (recur)))))

(defn rr-header
  [view]
  (let [tag (binf/rr-i8 view)
        len (binf/rr-i32 view)]
    {:tag tag :len len}))

(defn rr-auth-req
  [view]
  {:tag (binf/rr-i32 view)})

(defn rr-auth-salt
  [view]
  (let [ss (binf/rr-buffer view 4)]
    #?(:clj ss :cljs (js/Int8Array. ss))))

(defn rr-row-desc [view]
  (let [field-name (rr-cstring view)
        table-oid (binf/rr-i32 view)
        column-attr (binf/rr-i16 view)
        oid (binf/rr-i32 view)
        data-type-size (binf/rr-i16 view)
        type-mod (binf/rr-i32 view)
        format-code (binf/rr-i16 view)]
    {:field-name field-name
     :table-oid table-oid
     :column-attr column-attr
     :oid oid
     :data-type-size data-type-size
     :type-modifier type-mod
     :format-code format-code}))
