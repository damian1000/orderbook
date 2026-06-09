# TODO

- Decide between Story A ("concurrent in-memory data structure" — property-based tests, read snapshots, multi-threaded JMH) and Story B ("small matching engine" — partial fills, cancel/replace, market orders, execution reports, integer ticks, p50/p99/p99.9 latency histograms).
- Verify read-snapshot semantics are sufficient for intended consumers (multi-call reads can observe different book states).
- Track per-order queue position to make remove/modify O(log P) instead of O(log P + N_p).
- Add a price-level object that stores total size incrementally so `getTotalSize` is O(level).
- Add multi-threaded JMH benchmarks (read-heavy, write-heavy, mixed).
- Add allocation/GC profiling via JFR or async-profiler with results pinned in the README.
- Add a single-writer implementation alongside the lock-based one, benchmarked head-to-head.
- Add Lincheck linearizability testing layered over the existing concurrency stress tests.
