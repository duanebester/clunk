![Logo](assets/logos/clunk-dual.svg)

### /kləNGk/

*noun*

__A heavy, dull sound such as that made by thick pieces of metal striking together.__

Talking to Postgres via sockets. At this point in time, I'm just experimenting with Clojure; learning core.async.


[![Clojure CI](https://github.com/duanebester/clunk/actions/workflows/clojure.yml/badge.svg?branch=main)](https://github.com/duanebester/clunk/actions/workflows/clojure.yml)

### Example

clunk needs nothing but Clojure (or, on Node, nothing but Node). Its [core.async API](#coreasync) is optional.

```clj
(ns scratch.client
  (:require [clunk.core :as clunk]))

(def client (clunk/client {:username "jimmy"
                           :password "banana"
                           :database "world"}))

;; Authenticates (MD5 or SCRAM-SHA-256). connect returns a CompletableFuture
;; (a js Promise in ClojureScript) of the server's ReadyForQuery message, which
;; fails if connecting or authenticating does. Queries made before then wait their turn.
@(clunk/connect client)
```

For TLS, as hosted Postgres (RDS, Supabase, Neon, Cloud SQL) needs, set `:ssl` to a mode named as libpq's `sslmode` is. `:verify-full` (or `true`) checks that the server's certificate is signed by a trusted CA and names the host; `:verify-ca` checks only the CA; `:require` encrypts without checking; `:prefer` encrypts if the server offers TLS and otherwise doesn't; `:disable`, the default, never asks. Without `:ssl-root-cert`, the CAs trusted are the JVM's, or on Node, Node's bundled ones; providers whose CA isn't among them, such as RDS and Supabase, publish one to download. A failed handshake, or a server that won't talk TLS when the mode needs it, fails the connection with the reason: `client` throws on the JVM, and `connect`'s promise rejects on Node.

```clj
(clunk/client {:hostname "db.example.com" :username "jimmy" :password "banana" :database "world"
               :ssl :verify-full
               :ssl-root-cert "ca.pem"            ;; a file or PEM text; default: the platform's CAs
               ;; :ssl-cert "client.pem" :ssl-key "client.key" ;; for cert auth; on the JVM the key must be PKCS#8
               ;; :ssl-servername "db.example.com" ;; the name for SNI and the check, if not :hostname
               ;; :ssl-negotiation :direct         ;; Postgres 17+: start TLS at once, saving a round trip
               ;; :ssl-context ctx                 ;; JVM: your own javax.net.ssl.SSLContext
               })
```

On Node, the same settings are `ssl: 'verify-full'`, `sslRootCert`, `sslCert`, `sslKey` (each also a `Buffer`), `sslServername` and `sslNegotiation`, and `sslOptions` passes options to `tls.connect`.

`execute` returns a `CompletableFuture` of all the rows as a vector of maps, so `@` waits for them. A failed query fails the future; `@` then throws an `ExecutionException` whose cause is an `ex-info` with the Postgres `:code`, `:message`, and `:severity` in its `ex-data`. The next query still runs. Queries on one client run one at a time, in the order they were made; to run them in parallel, use a [pool](#a-pool).

```clj
@(clunk/execute client "SELECT name, region, population FROM country ORDER BY name LIMIT 3;")
;; [{:name "Afghanistan", :region "Southern and Central Asia", :population 22720000}
;;  {:name "Albania", :region "Southern Europe", :population 3401200}
;;  {:name "Algeria", :region "Northern Africa", :population 31471000}]
```

To pass values, give a vector of the SQL and its parameters, `$1`, `$2`, …, as next.jdbc does. clunk sends them apart from the SQL (with the extended query protocol), so they need no quoting or escaping. Postgres infers each parameter's type from the query; where nothing fixes it, as in `SELECT $1`, cast it (`$1::int4`). `nil` is `NULL`, and strings, numbers, booleans, UUIDs, byte arrays, `java.util.Date`, and `java.time` values all work. A query with parameters is a single statement; a plain string can hold several.

```clj
@(clunk/execute client ["SELECT name FROM country WHERE continent = $1 AND population > $2 ORDER BY name LIMIT 2" "Europe" 50000000])
;; [{:name "France"} {:name "Germany"}]
```

To handle rows as they arrive, without holding them all in memory, reduce `plan`. It works with `reduce`, `transduce`, `into`, and `run!`, and `take` or another `reduced` value stops it early. The server sends the rows in batches of 1000 (pass `{:fetch-size n}` as a third argument to change it) and sends the next batch only when clunk asks for it, so stopping early stops the server too. Like a query with parameters, a `plan` is a single statement. It blocks the reducing thread, so don't reduce it inside a go block. A failed query throws.

```clj
(transduce (map :population) + 0 (clunk/plan client "SELECT population FROM country;"))
;; 6078749450

(into [] (comp (map :name) (take 2)) (clunk/plan client "SELECT name FROM city ORDER BY name;"))
;; ["A Coruña (La Coruña)" "Aachen"]

(into [] (map :name) (clunk/plan client ["SELECT name FROM city WHERE countrycode = $1" "NLD"] {:fetch-size 100}))
```

#### Cancelling

To stop a query early from elsewhere, as when the HTTP client it serves has gone, pass a cancel token as `:cancel` to `execute` and fire it with `cancel!`. A query still waiting for a pool's connection then never runs. A running one stops reading rows, and since a query with `:cancel` has the server send its rows in batches of `:fetch-size` (1000 by default), the server stops after the current batch. Either way `execute` fails with an error whose `ex-data` has `:cancelled true`. As with `plan`, such a query is a single statement.

```clj
(let [token  (clunk/cancel-token)
      result (clunk/execute client "SELECT * FROM city" {:cancel token :fetch-size 100})]
  (clunk/cancel! token)
  (try @result
       (catch java.util.concurrent.ExecutionException e
         (ex-data (ex-cause e)))))
;; {:cancelled true}
```

`execute` and `plan` are built on `query`, which hands each row to a `clunk/Sink` as it arrives, and takes `:cancel` and `:fetch-size` too. It's there to adapt clunk to other ways of handling a stream of rows; `clunk.async` and the Node API use it.

#### A pool

A server handling requests at once needs a pool: a client runs one query at a time, so three one-second queries on a shared client take three seconds, and on a pool one. Everything that takes a client takes a pool too:

```clj
(require '[clunk.pool :as pool])

(def db (pool/pool {:username "jimmy" :password "banana" :database "world" :max-size 10}))

@(clunk/execute db "SELECT name FROM country ORDER BY name LIMIT 1;")
;; [{:name "Afghanistan"}]
```

A pool takes the same config as a client, plus `:max-size` (10 by default). It connects only when a query needs a connection and none is idle, so making one doesn't touch the server and there's no `connect` to call. Each query borrows a connection until its last row is read, or until it is cancelled: a `plan` reduce holds one until it returns, and a `clunk.async` channel until it closes. Queries beyond `:max-size` wait for a free connection, and a `:cancel` that fires while a query waits means it never runs. A connection the server dropped, whether idle or during a query, is replaced by a new one for the next query. A failed connect fails just the query that needed it.

`clunk/close` closes the idle connections at once and the busy ones as their queries finish. Queries after that fail with "Pool is closed".

#### In a Ring handler

The handlers below share the pool `db` above. On Java 21+, http-kit 2.8 runs handlers on virtual threads, where blocking on `@` costs next to nothing:

```clj
(require '[clojure.data.json :as json])

(defn countries [_request]
  {:status  200
   :headers {"content-type" "application/json"}
   :body    (json/write-str @(clunk/execute db "SELECT name, population FROM country ORDER BY population DESC LIMIT 3"))})
```

To stream a large result, hand `plan` to a response body. With the Ring Jetty adapter, this writes newline-delimited JSON as rows arrive. A slow reader slows the query, and a reader that disconnects gives the connection back to the pool. (http-kit doesn't support Ring's `StreamableResponseBody`.)

```clj
(require '[clojure.java.io :as io]
         '[ring.core.protocols :as ring])

(defn ndjson [rows]
  (reify ring/StreamableResponseBody
    (write-body-to-stream [_ _ out]
      (with-open [w (io/writer out)]
        (run! #(.write w (str (json/write-str %) "\n")) rows)))))

(defn cities [_request]
  {:status  200
   :headers {"content-type" "application/x-ndjson"}
   :body    (ndjson (clunk/plan db "SELECT name, population FROM city"))})
```

In an asynchronous Ring handler, complete the response from the future. Clojure 1.12 passes the `fn` as the `BiConsumer` that `whenCompleteAsync` takes.

The future completes on the thread that reads the connection, so a function given to `whenComplete`, `thenApply` or the like runs there, and the connection reads nothing more until it returns. It must not block, and must never wait for another query on the same client or pool: a pool may lend that very connection to the query, which then waits forever. `whenCompleteAsync` and the other `…Async` methods run it on another thread, so use them for anything that does real work, such as rendering a response:

```clj
(defn countries-async [_request respond raise]
  (.whenCompleteAsync (clunk/execute db "SELECT name FROM country ORDER BY name")
                      (fn [rows err]
                        (if err
                          (raise err)
                          (respond {:status 200 :body (json/write-str rows)})))))
```

#### LISTEN and NOTIFY

`listen` sends `LISTEN` and calls a function with each notification sent on that channel name, from any connection, as a map of `:channel`, `:payload`, and the notifying backend's `:pid`. They arrive whenever the server sends them, while the connection is idle or between a query's results. When the listener ends, the function is called once more, with an error value if it ended with one (check with `clunk/error?`), and otherwise with `nil`. `listen` returns a handle for `unlisten`. `notify` sends a notification with `pg_notify` and returns a future, or a js Promise, of `nil`:

```clj
(def events (clunk/listen client "events" (fn [n] (when n (println n)))))

@(clunk/notify db "events" "naïve café 🐘")
;; prints {:channel "events", :payload "naïve café 🐘", :pid 4242}

(clunk/unlisten client events) ;; or a channel name, to end all its listeners
```

The function never runs on the thread that reads the connection: each listener calls it from a thread of its own on the JVM, and from a microtask in JS, one notification at a time. So a slow function doesn't hold up queries; instead, a listener with `:max-queued` notifications waiting for it (`(clunk/listen client "events" f {:max-queued 1000})` is the default) ends with an error. If `LISTEN` fails, or the connection drops, every listener ends with an error; `clunk/close` and `unlisten` end them with `nil`. `unlisten` sends `UNLISTEN` once no listener is left on the name. On a pool, listeners share one connection of their own, which the pool never lends to queries and doesn't count toward `:max-size`.

```clj
(clunk/close client)
```

#### core.async

`clunk.async` has the core.async API: rows and notifications on channels. clunk doesn't depend on core.async, so add it to your own dependencies to use it:

```clj
;; deps.edn
org.clojure/core.async {:mvn/version "1.9.865"}
```

```clj
(require '[clojure.core.async :as async]
         '[clunk.async :as ca])
```

`ca/query` returns a channel of row maps that closes after the last row. It takes a string or a vector with parameters, as `execute` does. A failed query puts an `ex-info` on the channel (check with `clunk/error?`) instead of throwing. `ca/connect` returns a channel that yields the server's ReadyForQuery message or an error.

```clj
(async/go-loop [ch (ca/query client "SELECT name FROM country ORDER BY name LIMIT 3;")]
  (when-let [row (async/<! ch)]
    (println row)
    (recur ch)))
```

To stop early, pass a `:cancel` channel and close it. The row channel closes before the next query runs. With a `:cancel` channel, the server sends rows in batches of `:fetch-size` (1000 by default), so it stops after the current batch, whose remaining rows the client skips. A `timeout` channel works as a time limit. Closing the row channel yourself isn't enough, because the client may already be waiting to put the next row on it.

```clj
(let [cancel (async/promise-chan)
      rows   (ca/query client "SELECT * FROM city" {:cancel cancel})]
  (println (async/<!! rows))
  (async/close! cancel))

(ca/query client "SELECT * FROM city" {:cancel (async/timeout 1000)})

(ca/query client ["SELECT * FROM city WHERE population > $1" 1000000] {:cancel cancel :fetch-size 100})
```

A `promise-chan` or a `timeout` is the reliable kind of `:cancel` channel, because every check sees it once it fires. A value put on a plain channel is seen only once, so a pooled query that gets its connection just as the value arrives can still run, with its rows discarded.

Inside a go block, `<query!` parks until the query finishes and returns all its rows as a vector:

```clj
(async/go
  (println (ca/<query! client "SELECT 1::int4 AS one;")))
;; [{:one 1}]
```

`ca/listen` returns a channel of a channel name's notifications. The connection never waits for it: a channel that leaves `:max-queued` notifications untaken (1000 by default) gets an error value and closes. If `LISTEN` fails or the connection drops, it gets an error value and closes, and `clunk/close` closes it. Pass the channel to `clunk/unlisten` to stop; closing it yourself does the same when its next notification arrives.

```clj
(def events (ca/listen client "events"))
(async/<!! events)
;; {:channel "events", :payload "naïve café 🐘", :pid 4242}
(clunk/unlisten client events)
```

#### Logging

clunk logs through `System.Logger`, under the name `clunk`, on the JVM, so it goes wherever your JDK logging goes: to `java.util.logging` unless a bridge such as `slf4j-jdk-platform-logging` sends it elsewhere. It logs an error when it closes a connection after failing to read it, and when a `listen` function throws, and at debug level each message the server sends while the connection starts. On Node, errors go to `console.error`, and debug messages to `util.debuglog`, so `NODE_DEBUG=clunk` shows them.

### Node

The same code compiles to a CommonJS module with a Promise API, with nothing to install:

```bash
clojure -M:cljs release node   # writes target/node/clunk.js
```

```js
// example.mjs: an ES module, so it can await at the top level
import clunk from './target/node/clunk.js';

const client = await clunk.connect({ username: 'jimmy', password: 'banana', database: 'world' });
const rows = await clunk.query(client, 'SELECT name FROM country ORDER BY name LIMIT 2');
// [ { name: 'Afghanistan' }, { name: 'Albania' } ]

// An array of parameters fills in $1, $2, …, as in node-postgres.
const big = await clunk.query(client, 'SELECT name FROM country WHERE continent = $1 ORDER BY population DESC LIMIT $2', ['Europe', 2]);
// [ { name: 'Russian Federation' }, { name: 'Germany' } ]

// stream yields rows as they arrive, which the server sends in batches of 1000.
// break stops reading and the server, and the next query runs.
for await (const row of clunk.stream(client, 'SELECT name, population FROM city WHERE population > $1 ORDER BY population DESC', [1000000])) {
  console.log(row);
  if (row.population < 9000000) break;
}

clunk.close(client);
```

`Array.fromAsync` collects a stream into an array:

```js
const names = await Array.fromAsync(clunk.stream(client, 'SELECT name FROM country ORDER BY name LIMIT 3'));
// [ { name: 'Afghanistan' }, { name: 'Albania' }, { name: 'Algeria' } ]
```

Parameters can be strings, numbers, booleans, `null`, `Date`s (sent in local time with their offset, as node-postgres does), and `ArrayBuffer`s or `Buffer`s for `bytea`. Cast a parameter whose type the query doesn't fix, as in `SELECT $1::int4`. A query with parameters, and every `stream`, is a single statement.

A failed query rejects with an `Error` that has the Postgres `code` and `severity`, and the `sql`, as properties, as node-postgres errors do.

A server needs a pool, so that requests' queries run in parallel. `pool` takes the same config plus `maxSize` (10 by default), connects only as queries need connections, and replaces connections the server drops. `query` and `stream` take it in place of a client, and a stream holds its connection until it ends or you leave it:

```js
const pool = clunk.pool({ username: 'jimmy', password: 'banana', database: 'world', maxSize: 10 });
const rows = await clunk.query(pool, 'SELECT name FROM country ORDER BY name LIMIT 2');

clunk.close(pool); // closes idle connections now, busy ones when their queries finish
```

`stream` returns an async iterable, so `Readable.from` turns it into a Node stream. In an Express 5 handler, this streams rows as newline-delimited JSON. It waits while the HTTP client reads slowly, and gives the connection back to the pool if the HTTP client disconnects:

```js
import { Readable, Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';

app.get('/cities', async (req, res) => {
  res.type('application/x-ndjson');
  const toLine = new Transform({ objectMode: true, transform: (row, _, cb) => cb(null, JSON.stringify(row) + '\n') });
  await pipeline(Readable.from(clunk.stream(pool, 'SELECT * FROM city')), toLine, res);
});
```

Servers built on the web `fetch` API, such as Hono, take a `Response` whose body is a web `ReadableStream`, and `ReadableStream.from` accepts the same async iterable. When the client disconnects, the server cancels the body, which stops the stream:

```js
import { Hono } from 'hono';
import { serve } from '@hono/node-server';

const app = new Hono();
app.get('/cities', () => {
  const body = ReadableStream.from(clunk.stream(pool, 'SELECT name, population FROM city'))
    .pipeThrough(new TransformStream({ transform: (row, out) => out.enqueue(JSON.stringify(row) + '\n') }))
    .pipeThrough(new TextEncoderStream());
  return new Response(body, { headers: { 'content-type': 'application/x-ndjson' } });
});
serve({ fetch: app.fetch, port: 3000 });
```

`listen` is an async iterable of the notifications on a channel name, which leaving the loop unlistens, and `notify` sends one. As on the JVM, a pool listens on a connection of its own:

```js
for await (const { channel, payload, pid } of clunk.listen(pool, 'events')) {
  console.log(payload);
  if (payload === 'stop') break;
}

// Anywhere else:
await clunk.notify(pool, 'events', 'stop');
```

`unlisten(pool, 'events')` ends every `listen` on the name. A listener rejects if its connection drops, or if it falls 1000 notifications behind.

### Running The Tests

The tests need the Postgres from `docker-compose.yml`, with TLS turned on by a script that makes a test CA and certificates in `test/tls/certs` and installs them (it needs `openssl` and `psql`):

```bash
docker compose up -d
PGHOST=localhost PGUSER=jimmy PGPASSWORD=banana PGDATABASE=world test/tls/configure-postgres.sh
```

Run them on the JVM, then on Node:

```bash
clojure -X:test
clojure -M:cljs compile test && node target/test/node-tests.js
```

The ClojureScript build uses shadow-cljs, which needs Java 21 or newer.

A stress test of the pool runs outside the suite. It runs thousands of concurrent queries of every kind for a few minutes while killing connections, and checks every result, the pool's state, and the backends in `pg_stat_activity`:

```bash
clojure -M:stress 180 2000   # seconds, workers
clojure -M:cljs compile stress && node --expose-gc target/stress/stress.js 180 2000
```

A benchmark, also outside the suite, measures one client's read throughput on 1-row, 1,000-row and 100,000-row results, with one caller and with 100 sharing the client:

```bash
clojure -M:bench 5   # seconds per case
clojure -M:cljs compile bench && node target/bench/bench.js 5
clojure -M:bench 5 verify-full          # over TLS; add direct for direct negotiation
```

### Releasing

`build.clj` uses [tools.build](https://github.com/clojure/tools.build) and [deps-deploy](https://github.com/slipset/deps-deploy). The version is `0.0.<commit count>`.

```bash
clojure -T:build jar       # target/clunk-<version>.jar
clojure -T:build install   # into ~/.m2
CLOJARS_USERNAME=... CLOJARS_PASSWORD=... clojure -T:build deploy
```

clunk has no runtime dependencies beyond Clojure: the binary codec, [BinF](https://github.com/helins/binf.cljc), is vendored under [`src/clunk/vendor`](src/clunk/vendor/binf/README.md). The pom lists core.async as optional, for `clunk.async`, so Maven and tools.deps leave it out unless you add it.

Rough Todo / Help needed

* Flush out frontend/backend message parsers
* Publish the Node build to npm
