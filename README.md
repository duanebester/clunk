# Clunk

![Logo](assets/logos/clunk-dual.svg)

### /kləNGk/

*noun*

__A heavy, dull sound such as that made by thick pieces of metal striking together.__

Talking to Postgres via sockets. At this point in time, I'm just experimenting with Clojure; learning core.async.


[![Clojure CI](https://github.com/duanebester/clunk/actions/workflows/clojure.yml/badge.svg?branch=main)](https://github.com/duanebester/clunk/actions/workflows/clojure.yml)

### Running The Tests

You need to have a Postgres (13) Database up and running.

```bash
docker compose up -d
```

Now you can run the [tests](test/com/clunk/core_test.clj) with

```bash
clj -X:test
```

### Example

```clj
(ns scratch.client
  (:require
   [com.clunk.core :as clunk]))

(def cfg {:username "jimmy"
          :password "banana"
          :database "world"
          :debug true})
```

We can now create a client, and connect to the postgres instance:
```clj
(def client (clunk/client cfg))

(clunk/connect client)
```

We will see a bunch of received messages:
```bash
Connect: {:type :AuthenticationMD5, :salt #object["[B" 0x5ea94109 "[B@5ea94109"]}
Connect: {:type :AuthenticationOk}
Connect: {:type :ParameterStatus, :param "application_name", :status ""}
Connect: {:type :ParameterStatus, :param "client_encoding", :status "UTF8"}
Connect: {:type :ParameterStatus, :param "DateStyle", :status "ISO, MDY"}
Connect: {:type :ParameterStatus, :param "integer_datetimes", :status "on"}
Connect: {:type :ParameterStatus, :param "IntervalStyle", :status "postgres"}
Connect: {:type :ParameterStatus, :param "is_superuser", :status "on"}
Connect: {:type :ParameterStatus, :param "server_encoding", :status "UTF8"}
Connect: {:type :ParameterStatus, :param "server_version", :status "13.4 (Debian 13.4-1.pgdg100+1)"}
Connect: {:type :ParameterStatus, :param "session_authorization", :status "jimmy"}
Connect: {:type :ParameterStatus, :param "standard_conforming_strings", :status "on"}
Connect: {:type :ParameterStatus, :param "TimeZone", :status "Etc/UTC"}
Connect: {:type :BackendKeyData, :id 239, :key 2031531424}
Connect: {:type :ReadyForQuery, :status 73}
```

Finally we can define and run a query. The `<query!` macro is shorthand for an async take on the channel returned from `clunk/query`. It must be run inside a go block.
```clj
(def qs "SELECT name, region, population FROM country ORDER BY name LIMIT 10;")

(async/go-loop
 []
  (when-let [recv (clunk/<query! client qs)]
    (println recv)
    (recur)))
```

The above async loop prints out the following:
```bash
{:name Afghanistan, :region Southern and Central Asia, :population 22720000}
{:name Albania, :region Southern Europe, :population 3401200}
{:name Algeria, :region Northern Africa, :population 31471000}
{:name American Samoa, :region Polynesia, :population 68000}
{:name Andorra, :region Southern Europe, :population 78000}
{:name Angola, :region Central Africa, :population 12878000}
{:name Anguilla, :region Caribbean, :population 8000}
{:name Antarctica, :region Antarctica, :population 0}
{:name Antigua and Barbuda, :region Caribbean, :population 68000}
{:name Argentina, :region South America, :population 37032000}
```

And lastly, close the client:
```clj
(clunk/close client)
```

Rough Todo / Help needed

* Build out CLJS test suite
* Figure out a good way to provide async query results
* Fix core.async channel closing issues
* Flush out frontend/backend message parsers
* Deploy to Clojars
* Deploy to npm
