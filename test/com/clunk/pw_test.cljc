(ns com.clunk.pw-test
  #?(:clj
     (:require [com.clunk.pw :as pw]
               [clojure.test :as test])
     :cljs
     (:require [com.clunk.pw :as pw]
               [cljs.test :as test])))

#?(:cljs (enable-console-print!))

#?(:clj (test/deftest test-concat-byte-arrays
          (let [b1 #?(:clj (byte-array [1 2 3 4]) :cljs (.from js/Int8Array [1 2 3 4]))
                b2 #?(:clj (byte-array [5 6 7 8]) :cljs (.from js/Int8Array [5 6 7 8]))
                bb #?(:clj (byte-array [1 2 3 4 5 6 7 8]) :cljs (.from js/Int8Array [1 2 3 4 5 6 7 8]))
                b12 (pw/concat-byte-arrays b1 b2)]
            (test/is (= (seq bb) (seq b12)))))) ; fails in node. es6seqiterators aren't equal

(test/deftest test-calculate-pw
  (let [salt #?(:clj (byte-array (map byte [-1 2 -3 4])) :cljs (.from js/Int8Array [-1 2 -3 4]))
        p (pw/calculate-pw "user" "pass" salt)]
    (test/is (= p "md5ee1f8bf16ec3de5b9c97fe949e8ac674"))))

(test/deftest test-scram-sha-256-rfc7677-vector
  (let [scram {:password          "pencil"
               :client-first-bare "n=user,r=rOprNGfwEbeRWgbNEkqO"
               :server-first      "r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096"}
        final (pw/scram-client-final scram)]
    (test/is (= "c=biws,r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,p=dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ="
                (:message final)))
    (test/is (pw/scram-server-final-valid? final "v=6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4="))
    (test/is (not (pw/scram-server-final-valid? final "v=AAAATRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4=")))
    (test/is (thrown? #?(:clj Exception :cljs js/Error)
                      (pw/scram-client-final (assoc scram :server-first "r=someoneElsesNonce,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096"))))))

#?(:cljs
   (defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
     (if (cljs.test/successful? m)
       (println "Success!")
       (println "FAIL"))))

#?(:cljs (cljs.test/run-tests))
