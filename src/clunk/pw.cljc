(ns clunk.pw
  (:require [clojure.string :as str]
            #?@(:cljs [goog.crypt [cljs.nodejs :as node]]))
  #?(:clj
     (:import java.security.MessageDigest java.security.SecureRandom java.nio.ByteBuffer
              java.math.BigInteger java.util.Base64
              javax.crypto.Mac javax.crypto.SecretKeyFactory
              javax.crypto.spec.SecretKeySpec javax.crypto.spec.PBEKeySpec)))

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
             (let [ll (+ (.-byteLength b1) (.-byteLength b2))
                   bb (js/Int8Array. ll)
                   _ (.set bb (js/Int8Array. b1) 0)
                   _ (.set bb (js/Int8Array. b2) (.-byteLength b1))]
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
        hexarr #?(:clj (.getBytes hex "UTF-8") :cljs (js/Int8Array. (goog.crypt/stringToUtf8ByteArray hex)))]
    (str "md5" (bytes->md5-hex (concat-byte-arrays hexarr salt)))))

;; SCRAM-SHA-256 (RFC 5802 / RFC 7677), the default password auth since Postgres 14.
;; Channel binding is not supported, so the GS2 header is always "n,,".

(defn- utf8 [^String s]
  #?(:clj (.getBytes s "UTF-8") :cljs (.from js/Buffer s "utf8")))

(defn- b64-encode [bs]
  #?(:clj (.encodeToString (Base64/getEncoder) bs) :cljs (.toString (.from js/Buffer bs) "base64")))

(defn- b64-decode [^String s]
  #?(:clj (.decode (Base64/getDecoder) s) :cljs (.from js/Buffer s "base64")))

(defn- hmac-sha256 [key ^String msg]
  #?(:clj (let [mac (Mac/getInstance "HmacSHA256")]
            (.init mac (SecretKeySpec. key "HmacSHA256"))
            (.doFinal mac (utf8 msg)))
     :cljs (.digest (.update (.createHmac crypto "sha256" key) msg "utf8"))))

(defn- sha256 [bs]
  #?(:clj (.digest (MessageDigest/getInstance "SHA-256") bs)
     :cljs (.digest (.update (.createHash crypto "sha256") bs))))

(defn- pbkdf2-sha256 [^String password salt iterations]
  #?(:clj (.getEncoded (.generateSecret (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256")
                                        (PBEKeySpec. (.toCharArray password) salt iterations 256)))
     :cljs (.pbkdf2Sync crypto password salt iterations 32 "sha256")))

(defn- xor-bytes [a b]
  #?(:clj (byte-array (map bit-xor a b))
     :cljs (let [out (.alloc js/Buffer (.-length a))]
             (dotimes [i (.-length a)]
               (aset out i (bit-xor (aget a i) (aget b i))))
             out)))

(defn- parse-scram-attrs
  "\"r=abc,s=xyz,i=4096\" -> {\"r\" \"abc\", \"s\" \"xyz\", \"i\" \"4096\"}"
  [^String msg]
  (into {} (map #(let [i (.indexOf ^String % "=")] [(subs % 0 i) (subs % (inc i))]))
        (str/split msg #",")))

(defn scram-client-first
  "Starts a SCRAM exchange. Postgres ignores the SCRAM username in favor of the
   startup message's user, so it is sent empty, like libpq does."
  []
  (let [nonce (b64-encode #?(:clj (let [bs (byte-array 18)] (.nextBytes (SecureRandom.) bs) bs)
                             :cljs (.randomBytes crypto 18)))
        bare  (str "n=,r=" nonce)]
    {:client-first-bare bare
     :message           (str "n,," bare)}))

(defn scram-client-final
  "Answers the server-first-message. Returns the client-final-message to send and
   the server signature the server's final message must contain."
  [{:keys [password client-first-bare server-first]}]
  (let [{nonce "r" salt "s" iterations "i"} (parse-scram-attrs server-first)
        client-nonce     (get (parse-scram-attrs client-first-bare) "r")
        _                (when-not (and nonce (.startsWith ^String nonce client-nonce))
                           (throw (ex-info "SCRAM server nonce does not extend client nonce"
                                           {:server-first server-first})))
        without-proof    (str "c=biws,r=" nonce)
        auth-message     (str client-first-bare "," server-first "," without-proof)
        salted-password  (pbkdf2-sha256 password (b64-decode salt)
                                        #?(:clj (Integer/parseInt iterations) :cljs (js/parseInt iterations 10)))
        client-key       (hmac-sha256 salted-password "Client Key")
        client-signature (hmac-sha256 (sha256 client-key) auth-message)
        server-key       (hmac-sha256 salted-password "Server Key")]
    {:message          (str without-proof ",p=" (b64-encode (xor-bytes client-key client-signature)))
     :server-signature (b64-encode (hmac-sha256 server-key auth-message))}))

(defn scram-server-final-valid?
  "True when the server's final message proves it knows the password too."
  [{:keys [server-signature]} ^String server-final]
  (= (get (parse-scram-attrs server-final) "v") server-signature))
