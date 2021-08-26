(ns com.clunk.pw
  (:import (java.nio ByteBuffer)
           (java.security MessageDigest)
           (java.math BigInteger)))

(defn- concat-byte-arrays [& byte-arrays]
  (when (not-empty byte-arrays)
    (let [total-size (reduce + (map count byte-arrays))
          result     (byte-array total-size)
          bb         (ByteBuffer/wrap result)]
      (doseq [ba byte-arrays]
        (.put bb ^bytes ba))
      result)))

(defn- md5 [bs]
  (let [algorithm (MessageDigest/getInstance "MD5")
        raw (.digest algorithm bs)]
    (format "%032x" (BigInteger. 1 raw))))

(defn calculate-pw [^String user ^String password ^bytes salt]
  (let [hex (md5 (.getBytes (str password user)))]
    (str "md5" (md5 (concat-byte-arrays (.getBytes ^String hex) salt)))))