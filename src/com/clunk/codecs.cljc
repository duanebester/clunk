(ns com.clunk.codecs
  (:require [helins.binf :as binf]
            [helins.binf.string :as binf.string])
  #?(:clj (:import
           (java.nio ByteBuffer))))

(defn wr-cstring [view string]
  (binf/wr-string view (str string "\0"))
  view)

(defn rr-cstring [view]
  (let [pos (binf/position view)]
    (loop []
      (if (zero? (binf/rr-u8 view))
        (let [bb (binf/ra-buffer view pos (- (binf/position view) pos 1))]
          (.toString
           (.decode binf.string/decoder-utf-8
                    #?(:clj (ByteBuffer/wrap bb)
                       :cljs (js/Uint8Array. bb)))))
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
