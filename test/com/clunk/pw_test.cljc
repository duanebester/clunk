(ns com.clunk.pw-test
  #?(:clj
     (:require [com.clunk.pw :as pw]
               [clojure.test :as test])
     :cljs
     (:require [com.clunk.pw :as pw]
               [cljs.test :as test])))

#?(:cljs (enable-console-print!))

(test/deftest test-concat-byte-arrays
  (let [b1 #?(:clj (byte-array [1 2 3 4]) :cljs (.from js/Int8Array [1 2 3 4]))
        b2 #?(:clj (byte-array [5 6 7 8]) :cljs (.from js/Int8Array [5 6 7 8]))
        bb #?(:clj (byte-array [1 2 3 4 5 6 7 8]) :cljs (.from js/Int8Array [1 2 3 4 5 6 7 8]))
        b12 (pw/concat-byte-arrays b1 b2)]
    (println (type (.-buffer bb)))
    (println (type (.-buffer b12)))
    (test/is (= (seq bb) (seq b12))))) ; fails in node. es6seqiterators aren't equal

#?(:cljs
   (defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
     (if (cljs.test/successful? m)
       (println "Success!")
       (println "FAIL"))))

#?(:cljs (cljs.test/run-tests))
