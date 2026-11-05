(ns clunk.framing
  "Turns the raw byte chunks a socket reads into whole backend messages.

   TCP delivers a stream, not messages: one read can hold several messages, the
   end of one, or only part of a header. The message socket composes `frames`
   into the transducer of the channel the byte-buffer socket reads into, so
   the code above always sees exactly one complete message per value."
  (:require [clunk.vendor.binf :as binf]
            [clunk.vendor.binf.buffer :as buffer]))

(defn- message-size
  "Total bytes of the message starting at offset in view: the tag byte plus the
   int32 length, which counts itself and the body. nil when the header is
   incomplete."
  [view offset size]
  (when (<= (+ offset 5) size)
    (inc (binf/ra-i32 view (inc offset)))))

(defn- pending-buffer
  "A buffer for a message cut off after n of its bytes: the message's full
   size once its header is in, so later chunks fill it in place rather than
   growing it, and 5 bytes, the header, until then."
  [view offset n]
  (buffer/alloc (if (<= 5 n) (message-size view offset (+ offset n)) 5)))

(defn frames
  "Transducer from byte chunks (binf buffers: byte arrays on the JVM,
   ArrayBuffers in JS) to one binf view per complete message.

   Each message is a view into the chunk it arrived in, not a copy, so chunks
   must not be reused after they are put. Only a message cut across chunks is
   copied, once, into a buffer of its own."
  []
  (fn [rf]
    ;; A message the previous chunk cut off: its buffer and how many of its
    ;; bytes have arrived.
    (let [pending (volatile! nil)
          filled  (volatile! 0)]
      (fn
        ([] (rf))
        ([result] (rf result))
        ([result chunk]
         (let [size (count chunk)
               ;; Finish the cut-off message first, taking only the bytes it needs.
               [result offset]
               (if-let [buf @pending]
                 (let [have  @filled
                       ;; Up to 5 bytes complete the header, then the size is known.
                       taken (min size (max 0 (- 5 have)))
                       buf   (buffer/copy buf have chunk 0 taken)
                       have  (+ have taken)
                       total (message-size (binf/view buf) 0 have)
                       buf   (if (and total (< (count buf) total))
                               (buffer/copy (buffer/alloc total) 0 buf 0 have)
                               buf)
                       need  (if total (min (- size taken) (- total have)) 0)
                       buf   (buffer/copy buf have chunk taken need)
                       have  (+ have need)]
                   (if (= have total)
                     (do (vreset! pending nil)
                         [(rf result (binf/view buf)) (+ taken need)])
                     (do (vreset! pending buf)
                         (vreset! filled have)
                         [result size])))
                 [result 0])]
           (loop [result result
                  offset offset
                  whole  (binf/view chunk)]
             (cond
               (reduced? result) result
               (= offset size)   result
               :else
               (let [total (message-size whole offset size)]
                 (if (and total (<= (+ offset total) size))
                   (recur (rf result (binf/view chunk offset total)) (+ offset total) whole)
                   (let [n (- size offset)]
                     (vreset! pending (buffer/copy (pending-buffer whole offset n) 0 chunk offset n))
                     (vreset! filled n)
                     result)))))))))))
