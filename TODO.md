# TODO

- Decide between Story A ("concurrent in-memory data structure" — property-based tests, read snapshots, multi-threaded JMH) and Story B ("small matching engine" — partial fills, cancel/replace, market orders, execution reports, integer ticks, p50/p99/p99.9 latency histograms).
- Re-run JMH after the `Double?` change to `getPrice` and update README benchmark numbers.
- Verify read-snapshot semantics are sufficient for intended consumers (multi-call reads can observe different book states).
- Switch to a fair `ReentrantReadWriteLock` if writer starvation appears under heavy read load.
- Track per-order queue position to make remove/modify O(log P) instead of O(log P + N_p).
- Add a price-level object that stores total size incrementally so `getTotalSize` is O(level).
- Replace `LinkedList` with a custom intrusive linked list if cancel/modify latency matters.
- Add side-specific locks if bid/offer write contention becomes measurable.
- Add multi-threaded JMH benchmarks (read-heavy, write-heavy, mixed).
- Add allocation/GC profiling via JFR or async-profiler with results pinned in the README.
- Add a single-writer implementation alongside the lock-based one, benchmarked head-to-head.
- Add Lincheck linearizability testing layered over the existing concurrency stress tests.
- Review Gradle deprecation warnings before upgrading to Gradle 10.
- Enable Gradle configuration cache once the build is checked for compatibility.
- Add a Kotlin formatter/lint task.
