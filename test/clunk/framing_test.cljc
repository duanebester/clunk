(ns clunk.framing-test
  (:require #?(:clj [clojure.test :as t] :cljs [cljs.test :as t])
            [clunk.framing :as framing]
            [clunk.vendor.binf :as binf]))

(defn- message
  "A backend message as signed bytes: tag, big-endian int32 length (itself plus
   the body), body."
  [tag body]
  (let [len (+ 4 (count body))]
    (into [tag 0 0 (quot len 256) (- (mod len 256) (if (< (mod len 256) 128) 0 256))] body)))

(defn- ->buffer [byte-values]
  #?(:clj (byte-array byte-values)
     :cljs (.-buffer (js/Int8Array. (clj->js byte-values)))))

(defn- bytes-of [view]
  (vec #?(:clj (.array ^java.nio.ByteBuffer view)
          :cljs (js/Int8Array. (binf/backing-buffer view)))))

(def messages
  [(message 90 [73])                         ;; ReadyForQuery, 1-byte body
   (message 67 [])                           ;; empty body
   (message 68 (vec (take 300 (cycle (range -128 128)))))]) ;; length needs both low bytes

(t/deftest every-split-of-the-stream-yields-the-same-messages
  (let [stream (vec (apply concat messages))]
    (doseq [chunk-size (range 1 (inc (count stream)))]
      (let [chunks (map ->buffer (partition-all chunk-size stream))]
        (t/is (= messages (mapv bytes-of (into [] (framing/frames) chunks)))
              (str "chunk size " chunk-size))))))

(t/deftest an-unfinished-message-waits-for-more-bytes
  (let [[a b] messages
        part  (subvec b 0 3)]
    (t/is (= [a] (mapv bytes-of (into [] (framing/frames) [(->buffer (into a part))]))))))
