(ns clunk.framing
  "Turns the raw byte chunks a socket reads into whole backend messages.

   TCP delivers a stream, not messages: one read can hold several messages, the
   end of one, or only part of a header. The message socket composes `frames`
   into the transducer of the channel the byte-buffer socket reads into, so
   the code above always sees exactly one complete message per value."
  (:require [clunk.vendor.binf :as binf]
            [clunk.vendor.binf.buffer :as buffer]))

(defn- concat-buffers [a b]
  (let [na (count a)]
    (if (zero? na)
      b
      (-> (buffer/alloc (+ na (count b)))
          (buffer/copy 0 a 0 na)
          (buffer/copy na b 0 (count b))))))

(defn- slice [buf offset n]
  (buffer/copy (buffer/alloc n) 0 buf offset n))

(defn frames
  "Transducer from byte chunks (binf buffers: byte arrays on the JVM,
   ArrayBuffers in JS) to one binf view per complete message. A message is a
   1-byte tag and a big-endian int32 length that counts itself and the body.
   Bytes of an unfinished message wait for the next chunk."
  []
  (fn [rf]
    (let [pending (volatile! (buffer/alloc 0))]
      (fn
        ([] (rf))
        ([result] (rf result))
        ([result chunk]
         (let [buf  (concat-buffers @pending chunk)
               size (count buf)
               v    (binf/view buf)]
           (loop [result result
                  offset 0]
             (let [total (when (<= (+ offset 5) size)
                           (inc (binf/ra-i32 v (inc offset))))]
               (if (and total (<= (+ offset total) size))
                 (let [result (rf result (binf/view (slice buf offset total)))]
                   (if (reduced? result)
                     result
                     (recur result (+ offset total))))
                 (do (vreset! pending (slice buf offset (- size offset)))
                     result))))))))))
