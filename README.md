![Logo](assets/logos/clunk-dual.svg)

### /kləNGk/

*noun*

__A heavy, dull sound such as that made by thick pieces of metal striking together.__

Talking to Postgres via sockets. At this point in time, I'm just experimenting with Clojure; learning core.async.


[![Clojure CI](https://github.com/duanebester/clunk/actions/workflows/clojure.yml/badge.svg?branch=main)](https://github.com/duanebester/clunk/actions/workflows/clojure.yml)

### Example

```clj
(ns scratch.client
  (:require
   [clojure.core.async :as async]
   [clunk.core :as clunk]))

(def client (clunk/client {:username "jimmy"
                           :password "banana"
                           :database "world"}))

;; Authenticates (MD5 or SCRAM-SHA-256). The channel yields the server's
;; ReadyForQuery message, or an error. Queries made before then wait their turn.
(async/<!! (clunk/connect client))
```

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

#### A pool

A server handling requests at once needs a pool: a client runs one query at a time, so three one-second queries on a shared client take three seconds, and on a pool one. `execute`, `plan`, `query`, and `<query!` take a pool wherever they take a client:

```clj
(require '[clunk.pool :as pool])

(def db (pool/pool {:username "jimmy" :password "banana" :database "world" :max-size 10}))

@(clunk/execute db "SELECT name FROM country ORDER BY name LIMIT 1;")
;; [{:name "Afghanistan"}]
```

A pool takes the same config as a client, plus `:max-size` (10 by default). It connects only when a query needs a connection and none is idle, so making one doesn't touch the server and there's no `connect` to call. Each query borrows a connection until its last row is read, or until it is cancelled: a `plan` reduce holds one until it returns, and a `query` channel until it closes. Queries beyond `:max-size` wait for a free connection, and a `:cancel` that fires while a query waits means it never runs. A connection the server dropped, whether idle or during a query, is replaced by a new one for the next query. A failed connect fails just the query that needed it.

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

In an asynchronous Ring handler, complete the response from the future. Clojure 1.12 passes the `fn` as the `BiConsumer` that `whenComplete` takes:

```clj
(defn countries-async [_request respond raise]
  (.whenComplete (clunk/execute db "SELECT name FROM country ORDER BY name")
                 (fn [rows err]
                   (if err
                     (raise err)
                     (respond {:status 200 :body (json/write-str rows)})))))
```

#### core.async

`clunk/query` returns a channel of row maps that closes after the last row. It takes a string or a vector with parameters, as `execute` does. A failed query puts an `ex-info` on the channel (check with `clunk/error?`) instead of throwing.

```clj
(async/go-loop [ch (clunk/query client "SELECT name FROM country ORDER BY name LIMIT 3;")]
  (when-let [row (async/<! ch)]
    (println row)
    (recur ch)))
```

To stop early, pass a `:cancel` channel and close it. The row channel closes before the next query runs. With a `:cancel` channel, the server sends rows in batches of `:fetch-size` (1000 by default), so it stops after the current batch, whose remaining rows the client skips. A `timeout` channel works as a time limit. Closing the row channel yourself isn't enough, because the client may already be waiting to put the next row on it.

```clj
(let [cancel (async/promise-chan)
      rows   (clunk/query client "SELECT * FROM city" {:cancel cancel})]
  (println (async/<!! rows))
  (async/close! cancel))

(clunk/query client "SELECT * FROM city" {:cancel (async/timeout 1000)})

(clunk/query client ["SELECT * FROM city WHERE population > $1" 1000000] {:cancel cancel :fetch-size 100})
```

A `promise-chan` or a `timeout` is the reliable kind of `:cancel` channel, because every check sees it once it fires. A value put on a plain channel is seen only once, so a pooled query that gets its connection just as the value arrives can still run, with its rows discarded.

Inside a go block, `<query!` parks until the query finishes and returns all its rows as a vector:

```clj
(async/go
  (println (clunk/<query! client "SELECT 1::int4 AS one;")))
;; [{:one 1}]
```

```clj
(clunk/close client)
```

### Node

The same code compiles to a CommonJS module with a Promise API:

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

### Running The Tests

The tests need the Postgres from `docker-compose.yml`:

```bash
docker compose up -d
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

### Releasing

`build.clj` uses [tools.build](https://github.com/clojure/tools.build) and [deps-deploy](https://github.com/slipset/deps-deploy). The version is `0.0.<commit count>`.

```bash
clojure -T:build jar       # target/clunk-<version>.jar
clojure -T:build install   # into ~/.m2
CLOJARS_USERNAME=... CLOJARS_PASSWORD=... clojure -T:build deploy
```

clunk has no third-party runtime dependencies: the binary codec, [BinF](https://github.com/helins/binf.cljc), is vendored under [`src/clunk/vendor`](src/clunk/vendor/binf/README.md).

Rough Todo / Help needed

* Flush out frontend/backend message parsers
* Publish the Node build to npm
