(ns com.clunk.messages
  (:require [octet.core :as buf]
            [com.clunk.codecs :as codecs]))

(defn build-startup [^String user ^String database]
  (let [len (+ (count user) (count database) 16 4 4 1)
        codec (buf/spec buf/int32 buf/int32 codecs/cstring codecs/cstring codecs/cstring codecs/cstring buf/byte)
        data [(int len) (int 196608) "user" user "database" database (byte 0x00)]]
    (buf/into codec data)))

(defn build-password [^String password]
  (let [len (+ (count password) 1 4)
        codec (buf/spec buf/byte buf/int32 codecs/cstring)
        data [(byte 0x70) (int len) password]]
    (buf/into codec data)))

(defn build-query [^String query]
  (let [len (+ (count query) 1 4)
        codec (buf/spec buf/byte buf/int32 codecs/cstring)
        data [(byte 0x51) (int len) query]]
    (buf/into codec data)))
