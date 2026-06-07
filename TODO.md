# TODO

## Correctness And API Hardening

- Decide and document the expected behavior for invalid order data:
  - non-positive size
  - `NaN` or infinite price
  - negative price
  - duplicate id replacement semantics
- Consider returning a status from mutating operations, for example `Boolean`, so callers can tell whether `removeOrder` or `modifyOrder` actually changed the book.
- Consider replacing `Char` side values with an enum such as `Side.BID` / `Side.OFFER` to avoid runtime validation errors from typos.
## Concurrency

- Add stress tests for concurrent add, remove, modify, and read operations.
- Verify that read snapshots are sufficient for intended consumers. Current reads are consistent per method call, but multiple calls can still observe different book states.
- Consider whether writes should use a fair `ReentrantReadWriteLock` if writer starvation becomes a concern under heavy read load.

## Performance

- Track per-order queue position to make remove and modify closer to O(log P) instead of O(log P + N_p).
- Consider a price-level object that stores total size incrementally, which would make `getTotalSize` O(level) instead of O(level + N_p).
- Consider replacing `LinkedList` with a custom intrusive linked list if cancel/modify latency becomes important.
- Consider side-specific locks if write contention between bid and offer books becomes measurable.
- Add multi-threaded JMH benchmarks to measure lock contention and read/write mixes.
- Keep the existing single-threaded JMH benchmarks as a baseline for regression checks.

## Documentation

- Document whether adding an existing id should be treated as replacement, rejection, or an error.
- Document that this is an order book data structure, not a matching engine.
- Keep README benchmark values in sync with `build/reports/jmh/results.json` after performance-related changes.

## Build And Maintenance

- Review Gradle deprecation warnings before upgrading to Gradle 10.
- Consider enabling Gradle configuration cache once the build is checked for compatibility.
- Consider adding a formatting/lint task for Kotlin style consistency.
