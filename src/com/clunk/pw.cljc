(ns com.clunk.pw
  #?(:clj (:import java.security.MessageDigest java.math.BigInteger)
     :cljs (:require goog.crypt goog.crypt.Md5)))

(defn string->md5-hex
  "String goes in, md5 hex string comes out."
  [s]
  {:pre  [(string? s)]
   :post [(string? %)]}
  #?(:clj
     (let [algorithm (MessageDigest/getInstance "MD5")
           raw       (.digest algorithm (.getBytes s))]
       (format "%032x" (BigInteger. 1 raw)))
     :cljs
     (goog.crypt/byteArrayToHex
      (let [md5 (goog.crypt.Md5.)]
        (.update md5 (goog.crypt/stringToUtf8ByteArray s))
        (.digest md5)))))

(defn calculate-pw [^String user ^String password ^bytes salt]
  (let [bsalt #?(:clj (String. salt) :cljs (goog.crypt/utf8ByteArrayToString salt))
        hex   (string->md5-hex (str password user))]
    (str "md5" (string->md5-hex (str hex bsalt)))))