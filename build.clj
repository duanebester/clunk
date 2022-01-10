(ns build
  "clunk's build script.
   
  clojure -T:build ci
  clojure -T:build deploy
   
  Run tests via:
  clojure -X:test
   
  For more information, run:
  clojure -A:deps -T:build help/doc"
  (:require [clojure.tools.build.api :as b]
            [org.corfield.build :as bb]))

(def lib 'com.github.duanebester/clunk)
(defn- the-version [patch] (format "0.0.%s" patch))
(def version (the-version (b/git-count-revs nil)))
(def snapshot (the-version "999-SNAPSHOT"))

(defn ci "Run the CI pipeline of tests (and build the JAR)." [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/run-tests)
      (bb/clean)
      (assoc :src-pom "template/pom.xml")
      (bb/jar)))

(defn install "Install the JAR locally." [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/install)))

(defn deploy "Deploy the JAR to Clojars." [opts]
  (-> opts
      (assoc :lib lib :version (if (:snapshot opts) snapshot version))
      (bb/deploy)))