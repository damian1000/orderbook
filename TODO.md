# TODO

## Roadmap (prioritized)

External review picked this as the candidate flagship. Whether to actually flagship it depends on which story it tells — see P2 below.

### P1 — done

- `Char` side replaced with a `Side` enum (`BID` / `OFFER`); `'B'` / `'O'`
  preserved as serialization codes via `Side.fromCode(...)`. Typos are
  now a compile error.
- Invalid order data is rejected at the API boundary in `Order.init`:
  non-positive size, `NaN` / infinite price, and negative price all
  throw `IllegalArgumentException`. `modifyOrder` validates size too.
- `getPrice` returns `Double?` — `null` means "fewer than `level` price
  levels on this side", which is now distinct from a legitimate price
  of `0.0`. `level <= 0` is a programmer error and throws
  `IllegalArgumentException`.

### P2 — pick the story

The repo has two plausible identities. Pick one and commit; trying to be both ends up shallow on both.

- **Story A — "concurrent in-memory data structure".** Lean into what's already here. Add property-based tests, expose proper read snapshots, add multi-threaded JMH benchmarks (read-heavy, write-heavy, mixed). Keep `Double` for price — it's fine for a data-structure demo. Small repo, sharp focus, easy to read.
- **Story B — "small matching engine".** Bigger swing. Add real matching with partial fills, cancel/replace, market orders, execution reports. Switch to integer ticks (idiomatic in HFT). Latency histograms with p50/p99/p99.9 rather than just JMH average time. This is the version that earns "exchange tech" framing — but it's weeks of focused work, not days.

### P3 — stretch (only if Story B is chosen)

- Allocation/GC profiling using JFR or async-profiler, with results pinned in the README.
- A single-writer implementation alongside the lock-based one, benchmarked head-to-head.
- Linearizability testing (Lincheck on JVM) layered over the existing concurrency stress tests.

## Correctness And API Hardening

- Consider returning a status from mutating operations, for example `Boolean`, so callers can tell whether `removeOrder` or `modifyOrder` actually changed the book.
- Document duplicate-id replacement semantics more explicitly in `addOrder`'s contract (README already mentions it; the interface comment doesn't).
- Re-run JMH after the `Double?` change for `getPrice` — the benchmark numbers in the README pre-date the nullable return, and boxing may have shifted best-price lookup measurably.

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

- Document that this is an order book data structure, not a matching engine. (README already implies it under "What's missing for production"; could be more explicit in the project tagline.)
- Keep README benchmark values in sync with `build/reports/jmh/results.json` after performance-related changes.

## Build And Maintenance

- Review Gradle deprecation warnings before upgrading to Gradle 10.
- Consider enabling Gradle configuration cache once the build is checked for compatibility.
- Consider adding a formatting/lint task for Kotlin style consistency.
