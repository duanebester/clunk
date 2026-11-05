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

`execute` returns a `CompletableFuture` of all the rows as a vector of maps, so `@` waits for them. A failed query fails the future; `@` then throws an `ExecutionException` whose cause is an `ex-info` with the Postgres `:code`, `:message`, and `:severity` in its `ex-data`. The next query still runs. Queries on one client run one at a time, in the order they were made.

```clj
@(clunk/execute client "SELECT name, region, population FROM country ORDER BY name LIMIT 3;")
;; [{:name "Afghanistan", :region "Southern and Central Asia", :population 22720000}
;;  {:name "Albania", :region "Southern Europe", :population 3401200}
;;  {:name "Algeria", :region "Northern Africa", :population 31471000}]
```

To handle rows as they arrive, without holding them all in memory, reduce `plan`. It works with `reduce`, `transduce`, `into`, and `run!`, and `take` or another `reduced` value stops it early. It blocks the reducing thread, so don't reduce it inside a go block. A failed query throws.

```clj
(transduce (map :population) + 0 (clunk/plan client "SELECT population FROM country;"))
;; 6078749450

(into [] (comp (map :name) (take 2)) (clunk/plan client "SELECT name FROM city ORDER BY name;"))
;; ["A Coruña (La Coruña)" "Aachen"]
```

#### In a Ring handler

On Java 21+, http-kit 2.8 runs handlers on virtual threads, where blocking on `@` costs next to nothing:

```clj
(require '[clojure.data.json :as json])

(defn countries [_request]
  {:status  200
   :headers {"content-type" "application/json"}
   :body    (json/write-str @(clunk/execute client "SELECT name, population FROM country ORDER BY population DESC LIMIT 3"))})
```

To stream a large result, hand `plan` to a response body. With the Ring Jetty adapter, this writes newline-delimited JSON as rows arrive. A slow reader slows the query, and a reader that disconnects lets the next query run. (http-kit doesn't support Ring's `StreamableResponseBody`.)

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
   :body    (ndjson (clunk/plan client "SELECT name, population FROM city"))})
```

In an asynchronous Ring handler, complete the response from the future. Clojure 1.12 passes the `fn` as the `BiConsumer` that `whenComplete` takes:

```clj
(defn countries-async [_request respond raise]
  (.whenComplete (clunk/execute client "SELECT name FROM country ORDER BY name")
                 (fn [rows err]
                   (if err
                     (raise err)
                     (respond {:status 200 :body (json/write-str rows)})))))
```

#### core.async

`clunk/query` returns a channel of row maps that closes after the last row. A failed query puts an `ex-info` on the channel (check with `clunk/error?`) instead of throwing.

```clj
(async/go-loop [ch (clunk/query client "SELECT name FROM country ORDER BY name LIMIT 3;")]
  (when-let [row (async/<! ch)]
    (println row)
    (recur ch)))
```

To stop early, pass a `:cancel` channel and close it. The row channel closes, and the client skips the rest of the result before running the next query. A `timeout` channel works as a time limit. Closing the row channel yourself isn't enough, because the client may already be waiting to put the next row on it.

```clj
(let [cancel (async/promise-chan)
      rows   (clunk/query client "SELECT * FROM city" {:cancel cancel})]
  (println (async/<!! rows))
  (async/close! cancel))

(clunk/query client "SELECT * FROM city" {:cancel (async/timeout 1000)})
```

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

// stream yields rows as they arrive. break stops reading, and the next query runs.
for await (const row of clunk.stream(client, 'SELECT name, population FROM city ORDER BY population DESC')) {
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

A failed query rejects with an `Error` that has the Postgres `code` and `severity`, and the `sql`, as properties, as node-postgres errors do.

`stream` returns an async iterable, so `Readable.from` turns it into a Node stream. In an Express 5 handler, this streams rows as newline-delimited JSON. It waits while the HTTP client reads slowly, and lets the next query run if the HTTP client disconnects:

```js
import { Readable, Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';

app.get('/cities', async (req, res) => {
  res.type('application/x-ndjson');
  const toLine = new Transform({ objectMode: true, transform: (row, _, cb) => cb(null, JSON.stringify(row) + '\n') });
  await pipeline(Readable.from(clunk.stream(client, 'SELECT * FROM city')), toLine, res);
});
```

Servers built on the web `fetch` API, such as Hono, take a `Response` whose body is a web `ReadableStream`, and `ReadableStream.from` accepts the same async iterable. When the client disconnects, the server cancels the body, which stops the stream:

```js
import { Hono } from 'hono';
import { serve } from '@hono/node-server';

const app = new Hono();
app.get('/cities', () => {
  const body = ReadableStream.from(clunk.stream(client, 'SELECT name, population FROM city'))
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
