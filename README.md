# Clunk

<style>
    img.logo-dark {
        display: none;
        width: 300px;
    }
    img.logo-light {
        display: unset;
        width: 300px;
    }
    @media (prefers-color-scheme: dark) {
        img.logo-dark {
            display: unset;
        }
        img.logo-light {
            display: none;
        }
    }
</style>

<img class="logo-light" src="./assets/logos/clunk-black@2x.png" />
<img class="logo-dark" src="./assets/logos/clunk-white@2x.png" />

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
          :database "world"})
```

We can now create a client, and connect to the postgres instance:
```clj
(def client (clunk/client cfg))

(clunk/connect client)
```

We will see a bunch of received messages:
```bash
{:type :AuthenticationMD5, :salt [-47 -110 29 -114]}
{:type :AuthenticationOk}
{:type :ParameterStatus, :param application_name, :status }
{:type :ParameterStatus, :param client_encoding, :status UTF8}
{:type :ParameterStatus, :param DateStyle, :status ISO, MDY}
{:type :ParameterStatus, :param integer_datetimes, :status on}
{:type :ParameterStatus, :param IntervalStyle, :status postgres}
{:type :ParameterStatus, :param is_superuser, :status on}
{:type :ParameterStatus, :param server_encoding, :status UTF8}
{:type :ParameterStatus, :param server_version, :status 13.4 (Debian 13.4-1.pgdg100+1)}
{:type :ParameterStatus, :param session_authorization, :status jimmy}
{:type :ParameterStatus, :param standard_conforming_strings, :status on}
{:type :ParameterStatus, :param TimeZone, :status Etc/UTC}
{:type :BackendKeyData, :id 167, :key 835881833}
{:type :ReadyForQuery, :status 73}
```

Finally we can run a query:
```clj
(clunk/query client "SELECT name FROM country LIMIT 1;")
```

Which prints out the following:
```bash
{:type :RowDescription, ...}
{:type :DataRow, ...}
{:type :Close, ...}
{:type :ReadyForQuery, ...}
```

And lastly, close the client:
```clj
(clunk/close client)
```
