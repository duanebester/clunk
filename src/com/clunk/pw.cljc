(ns com.clunk.pw
  #?(:clj 
     (:import java.security.MessageDigest java.nio.ByteBuffer java.math.BigInteger)
     :cljs 
     (:require goog.crypt [cljs.nodejs :as node])))

#?(:cljs 
   (def crypto (node/require "crypto")))

(defn concat-byte-arrays [^bytes b1 ^bytes b2]
  #?(:clj (when (and (not-empty b1) (not-empty b2))
            (let [ll (+ (count b1) (count b2))
                  res (byte-array ll)
                  bb  (ByteBuffer/wrap res)
                  _ (.put bb ^bytes b1)
                  _ (.put bb ^bytes b2)]
              (.array bb)))
     :cljs (when (and (not-empty b1) (not-empty b2))
             (let [ll (+ (.-length b1) (.-length b2))
                   bb (js/Int8Array. ll)
                   _ (.set bb b1)
                   _ (.set bb b2 (count b1))]
               bb))))

(defn bytes->md5-hex
  "Bytes go in, md5 hex string comes out."
  [bs]
  #?(:clj
     (let [algorithm (MessageDigest/getInstance "MD5")
           raw       (.digest algorithm bs)]
       (format "%032x" (BigInteger. 1 raw)))
     :cljs
     (let [bbb (.from js/Buffer (js/Int8Array. bs))]
       (.digest (.update (.createHash crypto "md5") bbb) "hex"))))

(defn calculate-pw [user password ^bytes salt]
  (let [upass (str password user)
        upassarr #?(:clj (.getBytes upass "UTF-8") :cljs (goog.crypt/stringToUtf8ByteArray upass))
        hex (bytes->md5-hex upassarr)
        hexarr #?(:clj (.getBytes hex "UTF-8") :cljs (goog.crypt/stringToUtf8ByteArray hex))]
    (str "md5" (bytes->md5-hex (concat-byte-arrays hexarr salt)))))
