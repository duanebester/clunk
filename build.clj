(ns build
  "clunk's build script.

  clojure -T:build jar      ; target/clunk-<version>.jar
  clojure -T:build install  ; into ~/.m2
  clojure -T:build deploy   ; to Clojars, with CLOJARS_USERNAME and CLOJARS_PASSWORD set

  Pass :snapshot true to build 0.0.999-SNAPSHOT instead of the release version."
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.github.duanebester/clunk)
(def class-dir "target/classes")

(defn- version [{:keys [snapshot]}]
  (format "0.0.%s" (if snapshot "999-SNAPSHOT" (b/git-count-revs nil))))

(defn- jar-file [opts]
  (format "target/%s-%s.jar" (name lib) (version opts)))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [opts]
  (clean opts)
  (let [basis   (b/create-basis {:project "deps.edn"})
        version (version opts)]
    (b/write-pom {:class-dir class-dir
                  :lib       lib
                  :version   version
                  :basis     basis
                  :src-dirs  ["src"]
                  :scm       {:url                 "https://github.com/duanebester/clunk"
                              :connection          "scm:git:git://github.com/duanebester/clunk.git"
                              :developerConnection "scm:git:ssh://git@github.com/duanebester/clunk.git"
                              :tag                 (b/git-process {:git-args "rev-parse HEAD"})}
                  :pom-data  [[:description "Talking to Postgres via sockets, from Clojure and ClojureScript"]
                              [:url "https://github.com/duanebester/clunk"]
                              [:licenses
                               [:license
                                [:name "Eclipse Public License"]
                                [:url "http://www.eclipse.org/legal/epl-v10.html"]]]
                              [:developers
                               [:developer [:name "Duane Bester"]]]]})
    (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
    (b/jar {:class-dir class-dir :jar-file (jar-file opts)})
    (println "Wrote" (jar-file opts))))

(defn install [opts]
  (jar opts)
  (b/install {:basis     (b/create-basis {:project "deps.edn"})
              :lib       lib
              :version   (version opts)
              :jar-file  (jar-file opts)
              :class-dir class-dir}))

(defn deploy [opts]
  (jar opts)
  (dd/deploy {:installer :remote
              :artifact  (b/resolve-path (jar-file opts))
              :pom-file  (b/pom-path {:lib lib :class-dir class-dir})}))
