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

To update, copy those files from a newer upstream tag, repeat the rename, and
reapply the reflection fixes:

```sh
sed -i 's/helins\.binf/clunk.vendor.binf/g' $(find src/clunk/vendor -name '*.clj*')
```
