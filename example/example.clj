(require '[clunk.core :as clunk]
         '[clojure.core.async :as async])

(def client (clunk/client {:username "jimmy"
                           :password "banana"
                           :database "world"}))

(println (async/<!! (clunk/connect client)))

(doseq [row (async/<!! (async/into [] (clunk/query client "select * from country limit 10;")))]
  (println row))

(clunk/close client)
