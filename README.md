# Clunk

### /kləNGk/

*noun*

__A heavy, dull sound such as that made by thick pieces of metal striking together.__

Talking to Postgres via sockets. At this point in time, I'm just experimenting with Clojure; learning core.async.

### Running The Tests

You need to have a Postgres (13) Database up and running.

```bash
docker compose up -d
```

Now you can run the [tests](test/com/clunk/core_test.clj) with

```bash
clj -X:test
```

### [Example](example/example.clj)

Require the following:
```clj
(require '[com.clunk.core :as clunk]
         '[clojure.core.async :as async]
         '[clojure.core.match :as m])

(def username "jimmy")
(def password "banana")
(def database "world")
(def port (int 5432))
```

We can now create a client, and a receive handler that responds to an MD5 Authentication request, and also prints all received messages:
```clj
(def client (clunk/socket-client port))

;; Receive Handler
(async/go-loop []
  (when-let [msg (async/<! (:in client))]
    ;; Print any received message
    (println (str "Received Message: " msg))
    (m/match msg
      {:type :AuthenticationMD5}
      ;; Send MD5 password with received salt
      (async/>! (:out client) (clunk/get-password username password (byte-array (:salt msg))))
      :else nil)
    (recur)))
```

Now we can send the Startup Message:
```clj
(async/>!! (:out client) (clunk/get-startup username database))
```

We will see a bunch of received messages:
```bash
Received Message: {:type :AuthenticationMD5, :salt [-47 -110 29 -114]}
Received Message: {:type :AuthenticationOk}
Received Message: {:type :ParameterStatus, :status ["application_name" ""]}
Received Message: {:type :ParameterStatus, :status ["client_encoding" "UTF8"]}
Received Message: {:type :ParameterStatus, :status ["DateStyle" "ISO, MDY"]}
Received Message: {:type :ParameterStatus, :status ["integer_datetimes" "on"]}
Received Message: {:type :ParameterStatus, :status ["IntervalStyle" "postgres"]}
Received Message: {:type :ParameterStatus, :status ["is_superuser" "on"]}
Received Message: {:type :ParameterStatus, :status ["server_encoding" "UTF8"]}
Received Message: {:type :ParameterStatus, :status ["server_version" "13.4 (Debian 13.4-1.pgdg100+1)"]}
Received Message: {:type :ParameterStatus, :status ["session_authorization" "jimmy"]}
Received Message: {:type :ParameterStatus, :status ["standard_conforming_strings" "on"]}
Received Message: {:type :ParameterStatus, :status ["TimeZone" "Etc/UTC"]}
Received Message: {:type :BackendKeyData, :id 167, :key 835881833}
Received Message: {:type :ReadyForQuery, :status 73}
```

Finally we can run a query:
```clj
(async/>!! (:out client) (clunk/get-query "select 1 as x, 2 as y;"))
```

Which produces the following:
```bash
Received Message: {:type :RowDescription, ...}
Received Message: {:type :DataRow, ...}
Received Message: {:type :Close, ...}
Received Message: {:type :ReadyForQuery, ...}
```

And lastly, close the client:
```clj
(clunk/close-client client)
```