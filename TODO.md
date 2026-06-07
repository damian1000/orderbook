# TODO

## Roadmap (prioritized)

External review picked this as the candidate flagship. Whether to actually flagship it depends on which story it tells — see P2 below.

### P1 — quick correctness/API wins

- **Replace `Char` with a `Side` enum.** Stops a whole class of bugs (`getPrice('X', 1)` is currently caught at runtime; an enum makes it a compile error). Already noted in API hardening below.
- **Reject invalid order data instead of silently passing it through.** Non-positive size, negative price, `NaN`/infinite price should throw at the API boundary, not flow through to corrupt the book.
- **Stop using `0.0` as a missing-level sentinel for `getPrice`.** It collides with a legitimate price of zero and a legitimate empty-side query. Return `null` (Kotlin nullable) or throw on out-of-range level; pick one and document it.

### P2 — pick the story

The repo has two plausible identities. Pick one and commit; trying to be both ends up shallow on both.

- **Story A — "concurrent in-memory data structure".** Lean into what's already here. Add property-based tests, expose proper read snapshots, add multi-threaded JMH benchmarks (read-heavy, write-heavy, mixed). Keep `Double` for price — it's fine for a data-structure demo. Small repo, sharp focus, easy to read.
- **Story B — "small matching engine".** Bigger swing. Add real matching with partial fills, cancel/replace, market orders, execution reports. Switch to integer ticks (idiomatic in HFT). Latency histograms with p50/p99/p99.9 rather than just JMH average time. This is the version that earns "exchange tech" framing — but it's weeks of focused work, not days.

### P3 — stretch (only if Story B is chosen)

- Allocation/GC profiling using JFR or async-profiler, with results pinned in the README.
- A single-writer implementation alongside the lock-based one, benchmarked head-to-head.
- Linearizability testing (Lincheck on JVM) layered over the existing concurrency stress tests.

## Correctness And API Hardening

- Decide and document the expected behavior for invalid order data:
  - non-positive size
  - `NaN` or infinite price
  - negative price
  - duplicate id replacement semantics
- Consider returning a status from mutating operations, for example `Boolean`, so callers can tell whether `removeOrder` or `modifyOrder` actually changed the book.
- Consider replacing `Char` side values with an enum such as `Side.BID` / `Side.OFFER` to avoid runtime validation errors from typos.
## Concurrency

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
