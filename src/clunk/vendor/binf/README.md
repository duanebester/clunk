# Vendored BinF

Source: [helins/binf.cljc](https://github.com/helins/binf.cljc) 1.1.0, commit
`997f074aeb304f09dcde35e8cb4db20e87c12f27`, licensed under the Mozilla Public
License 2.0 (see `LICENSE` in this directory).

Only the namespaces clunk needs are included: `binf`, `buffer`, `int`,
`protocol`, `protocol.impl`, and `string`. Changes from upstream:

- The namespace prefix, `helins.binf` → `clunk.vendor.binf`, so these copies
  cannot clash with a real BinF on a consumer's classpath.
- `protocol/impl.clj` passes `int` positions and limits to `ByteBuffer` and
  type-hints the views it chains on. Upstream calls those methods through
  reflection, which was two thirds of clunk's time decoding large results.
  `(set! *warn-on-reflection* true)` before requiring it should print nothing.
- `rr-string` on a heap `ByteBuffer` builds the `String` straight from the
  backing array, rather than through `Charset.decode` and a `CharBuffer`, and
  keeps the position arithmetic unboxed. That made 100,000-row reads about 30%
  faster.

To update, copy those files from a newer upstream tag, repeat the rename, and
reapply the reflection and `rr-string` fixes:

```sh
sed -i 's/helins\.binf/clunk.vendor.binf/g' $(find src/clunk/vendor -name '*.clj*')
```
