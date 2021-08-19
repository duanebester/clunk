# Clunk

Connecting to Postgres w/ sockets.

### Running The Tests

You need to have a Postgres (13) Database up and running.

```bash
docker compose up -d
```

Now you can run the [tests](test/com/clunk/core_test.clj) with

```bash
clj -X:test
```