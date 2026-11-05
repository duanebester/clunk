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

`clunk/query` returns a channel of row maps that closes after the last row. Queries on one client run one at a time, in the order they were made.

```clj
(async/go-loop [ch (clunk/query client "SELECT name, region, population FROM country ORDER BY name LIMIT 3;")]
  (when-let [row (async/<! ch)]
    (println row)
    (recur ch)))
;; {:name Afghanistan, :region Southern and Central Asia, :population 22720000}
;; {:name Albania, :region Southern Europe, :population 3401200}
;; {:name Algeria, :region Northern Africa, :population 31471000}
```

Inside a go block, `<query!` parks until the query finishes and returns all its rows as a vector:

```clj
(async/go
  (println (clunk/<query! client "SELECT 1::int4 AS one;")))
;; [{:one 1}]
```

A failed query puts an `ex-info` on the channel (check with `clunk/error?`); its `ex-data` holds the Postgres `:code`, `:message`, and `:severity`. The next query still runs.

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
clunk.close(client);
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
