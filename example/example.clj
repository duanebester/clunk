(require '[clunk.core :as clunk])

(def client (clunk/client {:username "jimmy"
                           :password "banana"
                           :database "world"}))

(println @(clunk/connect client))

(doseq [row @(clunk/execute client "select * from country limit 10;")]
  (println row))

(clunk/close client)
