(ns clunk.framing
  "Turns the raw byte chunks a socket reads into whole backend messages.

   TCP delivers a stream, not messages: one read can hold several messages, the
   end of one, or only part of a header. The message socket composes `frames`
   into the transducer of the channel the byte-buffer socket reads into, so
   the code above always sees exactly one complete message per value."
  (:require [clunk.vendor.binf :as binf]
            [clunk.vendor.binf.buffer :as buffer]))

(defn- append [a an b boff bn]
  (-> (buffer/alloc (+ an bn))
      (buffer/copy 0 a 0 an)
      (buffer/copy an b boff bn)))

(defn- message-size
  "Total bytes of the message starting at offset in view: the tag byte plus the
   int32 length, which counts itself and the body. nil when the header is
   incomplete."
  [view offset size]
  (when (<= (+ offset 5) size)
    (inc (binf/ra-i32 view (inc offset)))))

(defn frames
  "Transducer from byte chunks (binf buffers: byte arrays on the JVM,
   ArrayBuffers in JS) to one binf view per complete message.

   Each message is a view into the chunk it arrived in, not a copy, so chunks
   must not be reused after they are put. Only a message cut across two chunks
   is copied, into a buffer of its own."
  []
  (fn [rf]
    ;; Bytes of a message that the previous chunk cut off, waiting for the rest.
    (let [pending (volatile! nil)]
      (fn
        ([] (rf))
        ([result] (rf result))
        ([result chunk]
         (let [size (count chunk)
               ;; Finish the cut-off message first, taking only the bytes it needs.
               [result offset]
               (if-let [head @pending]
                 (let [hn    (count head)
                       ;; Up to 5 bytes complete the header, then the size is known.
                       head  (if (< hn 5) (append head hn chunk 0 (min size (- 5 hn))) head)
                       taken (- (count head) hn)
                       total (message-size (binf/view head) 0 (count head))]
                   (if (nil? total)
                     (do (vreset! pending head) [result size])
                     (let [need (min (- size taken) (- total (count head)))
                           msg  (append head (count head) chunk taken need)]
                       (if (= (count msg) total)
                         (do (vreset! pending nil)
                             [(rf result (binf/view msg)) (+ taken need)])
                         (do (vreset! pending msg) [result size])))))
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
                   (do (vreset! pending (append chunk 0 chunk offset (- size offset)))
                       result)))))))))))
