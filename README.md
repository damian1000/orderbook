# Kotlin Order Book

[![CI](https://github.com/damian1000/kotlin-orderbook/actions/workflows/ci.yml/badge.svg)](https://github.com/damian1000/kotlin-orderbook/actions/workflows/ci.yml)
[![CodeQL](https://github.com/damian1000/kotlin-orderbook/actions/workflows/codeql.yml/badge.svg)](https://github.com/damian1000/kotlin-orderbook/actions/workflows/codeql.yml)
[![codecov](https://codecov.io/gh/damian1000/kotlin-orderbook/graph/badge.svg)](https://codecov.io/gh/damian1000/kotlin-orderbook)

A small, thread-safe **limit order book** and **price-time-priority matching engine** in Kotlin. Add / modify / remove orders, query the book by side and level, preserving time priority across modifications — then submit crossing orders and watch them match.

**▶ Try it live:** https://orderbook.damianhoward.com — submit an order and watch it match resting liquidity and print to the trade tape.

## Problem

Build an `OrderBook` that supports:

1. **Add** an `Order(id, price, side, size)` to the book. Order additions are expected to be the most frequent operation.
2. **Remove** an order by `id`. Removals run at roughly 60% of the add rate.
3. **Modify** the size of an existing order. **Size modifications must not affect time priority.**
4. **Get price** for `(side, level)` — `level=1` returns the best price on that side, `level=2` the second-best, etc.
5. **Get total size** for `(side, level)` — sum of sizes at that price level.
6. **Get orders** for a side, ordered by level then time of arrival.

Sides: `Side.BID` (buy) and `Side.OFFER` (sell), with `'B'` / `'O'` retained as serialization codes via `Side.fromCode(...)`. Best bid = highest price; best offer = lowest price.

## Design

- **Prices are integer ticks.** `Price` is a `@JvmInline value class` over a scaled `Long` (`SCALE = 8` decimal places). Scaled integers keep prices exact — no binary floating-point drift, so two logically equal prices always share a map key — and make comparison a primitive `Long` op. `BigDecimal` is touched only when parsing or formatting a decimal string, never on the matching path.
- **`PlainOrderBook`** holds the data structure and algorithms, with no concurrency control:
  - **`buyOrders`**: `TreeMap<Price, LinkedList<Order>>`, reverse ordering — highest bid first.
  - **`sellOrders`**: `TreeMap<Price, LinkedList<Order>>`, natural ordering — lowest offer first.
  - **`ordersMap`**: `HashMap<Long, Order>` for O(1) lookup by id (needed for remove / modify).
  - Per-price queues are `LinkedList<Order>` so insertion order = time priority.
- **Two concurrency strategies wrap that core**, differing only in how they serialise access — `LockingOrderBook` (a read/write lock) and `SingleWriterOrderBook` (one owning thread). See [Concurrency](#concurrency).
- **Time priority preserved on modify**: replacing an order in its `LinkedList` preserves its position — it is not moved to the tail. This is a deliberate simplification: real venues keep queue priority on a size _decrease_ but send a size _increase_ to the back of the queue. Here any size change retains its place.
- Adding an existing id replaces the old visible order first, so duplicate ids do not leave stale orders at old price levels.

## Complexity

| Operation                   | Cost                                                     | Notes                                                                                                              |
| --------------------------- | -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `addOrder`                  | **O(log P)**                                             | `P` = distinct price levels on that side; replacing an existing id also removes its old queue entry                |
| `removeOrder`               | **O(log P + N_p)**                                       | id-lookup O(1); removing from the `LinkedList` is O(N) at that price level                                         |
| `modifyOrder`               | **O(log P + N_p)**                                       | finds and replaces the existing queue element in place                                                             |
| `getPrice(side, level)`     | **O(log P)** for level 1, otherwise **O(log P + level)** | `TreeMap.firstKey()` walks the tree to the leftmost node for the best price; higher levels iterate keys from there |
| `getTotalSize(side, level)` | **O(level + N_p)**                                       | sums the LinkedList at that level                                                                                  |
| `getOrders(side)`           | **O(P + N)**                                             | walks all per-price queues                                                                                         |

The remove/modify cost could be O(log P) instead of O(log P + N_p) by tracking each order's node in its `LinkedList` (or replacing with an indexed map). Trade-off is more bookkeeping for a rarely-hot path.

## Concurrency

Two interchangeable `OrderBook` implementations share the same `PlainOrderBook` core:

- **`LockingOrderBook` (lock-based, default).** Reads (`getPrice`, `getTotalSize`, `getOrders`) take a read lock and see a consistent snapshot for that call; writes (`addOrder`, `modifyOrder`, `removeOrder`) take the write lock. The read/write split lets reads run concurrently.
- **`SingleWriterOrderBook`.** One owning thread runs every operation serially; callers hand work off and await the result, so no locks touch the data structures. This is the practical "lockless" design used by low-latency engines (the single-writer principle), rather than a lock-free `TreeMap`, which isn't achievable. It is `AutoCloseable` — `close()` stops the writer thread.

The [benchmarks](#benchmarks) compare the two under contention.

## Matching engine

`MatchingEngine` adds price-time-priority matching on top of any `OrderBook`. A submitted order crosses the best opposite levels first, filling the oldest resting order at each level before moving on; each match prints a `Trade` at the **resting** order's price (so price improvement accrues to the taker), and any unfilled remainder rests on the book as a passive limit order.

It drives only the public `add` / `remove` / `modify` / query contract — never the book's internals — so the data structure stays a clean, independently-benchmarked component and either concurrency strategy can be matched on.

```kotlin
val engine = MatchingEngine(LockingOrderBook())
engine.submit(Order(1L, Price.of("101"), Side.OFFER, 5))        // rests: nothing to cross
val fills = engine.submit(Order(2L, Price.of("101"), Side.BID, 8))
// fills = [Trade(101.00, size 5, resting=1, incoming=2, BID)]; the remaining 3 rests as the best bid
```

Scope: plain limit orders (cross, then rest the remainder). Richer types — market, IOC/FOK, stop, iceberg — build on this. The [live web front end](#kotlin-order-book) wraps the engine in a dependency-free JDK `HttpServer`, pushing book and tape updates to the browser over Server-Sent Events.

## Run

```bash
./gradlew test     # behavioural contract run against both books + concurrency stress tests
./gradlew jmh      # JMH micro-benchmarks (single-threaded + contended head-to-head)
./gradlew run      # the live order-book + matching web app on http://localhost:8080
```

## Benchmarks

JMH, JDK 25, book pre-populated with 10,000 orders across 50 price levels, on an 8-core development laptop. **Indicative, not publication-grade** — reproduce on your own hardware before drawing conclusions.

### Single-threaded, lock-based (average time per op)

3×2s warmup + 5×2s measurement, single fork.

| Operation                               |   Avg time |
| --------------------------------------- | ---------: |
| `getPrice` best bid, level 1            |  **16 ns** |
| `getPrice` best offer, level 1          |  **18 ns** |
| `getTotalSize` at level 5               | **312 ns** |
| `modifyOrder` (existing id, same price) | **295 ns** |
| `addOrder` + `removeOrder` pair         | **493 ns** |

- Best-price lookup is ~16 ns (read-lock overhead included). `TreeMap.firstKey()` is **O(log P)** — it walks the tree's left spine and the result isn't cached — but with ~50 price levels that's only a handful of pointer hops, so it measures effectively flat. Moving price from `Double` to a `Price(Long)` value class did not regress it.
- The add/remove pair under ~500 ns is the honest steady-state number — book size is stationary across the window. (A standalone `addOrder` row is omitted: the book grows unboundedly inside the measurement window, so its average mixes many book sizes.)

### Lock vs single-writer, contended (8 threads, throughput, higher is better)

5×2s warmup + 8×2s measurement, 2 forks.

| Workload                           | `LockingOrderBook` (lock) | `SingleWriterOrderBook` |
| ---------------------------------- | ------------------------: | ----------------------: |
| read-heavy (best bid + best offer) |      **2719 ± 94** ops/ms |         250 ± 11 ops/ms |
| mixed (~90% reads)                 |    **1378 ± 1058** ops/ms |         447 ± 11 ops/ms |
| write-heavy (add + remove)         |      **223 ± 135** ops/ms |         168 ± 27 ops/ms |

Reading the table:

- **The read/write lock wins on throughput across the board** on this hardware. Its read lock lets queries run concurrently, so read-heavy and mixed workloads scale well past a design that serialises every read through one thread.
- **But the lock's throughput is wildly variable under write contention** (±1058 on mixed, ±135 on write-heavy) while the single writer is rock-steady (±11–27). Predictable latency is what a matching engine actually cares about, so stability — not raw throughput — is the single-writer's selling point here.
- **The naive single writer doesn't realise the theoretical win** because its hand-off still goes through a blocking queue and `Future.get()` (locks and thread parking) — it relocates locking rather than removing it, and adds cross-core wakeup latency. Turning the stability into a throughput win would need a Disruptor-style busy-spin ring buffer; that's the natural follow-up.

Reproduce:

```bash
./gradlew jmhJar
java -jar build/libs/kotlin-orderbook-1.0.0-jmh.jar Contended -f 2 -wi 5 -i 8
java -jar build/libs/kotlin-orderbook-1.0.0-jmh.jar OrderBookBenchmark -p impl=lock -f 1
```

## Use it

```kotlin
val book: OrderBook = LockingOrderBook()

book.addOrder(Order(id = 1L, price = Price.of("19"), side = Side.OFFER, size = 8))
book.addOrder(Order(id = 2L, price = Price.of("21"), side = Side.OFFER, size = 16))
book.addOrder(Order(id = 3L, price = Price.of("15"), side = Side.BID,   size = 5))

book.getPrice(Side.OFFER, 1)       // 19.00000000  — best offer
book.getPrice(Side.BID,   1)       // 15.00000000  — best bid
book.getPrice(Side.OFFER, 9)       // null  — fewer than 9 levels exist
book.getTotalSize(Side.OFFER, 1)   // 8

book.modifyOrder(orderId = 1L, size = 12)   // size 8 -> 12, time priority preserved
book.removeOrder(orderId = 2L)
```

Sides are an enum (`Side.BID` / `Side.OFFER`) — typos are a compile error,
not a runtime exception. Prices are exact integer ticks via `Price`, so
`Price.of("3.45")` round-trips without floating-point drift; over-precise
(beyond `SCALE` decimals) or negative prices are rejected at construction, and
non-positive sizes are rejected at `Order` construction — a corrupt order can't
reach the book. `getPrice` returns `Price?`: `null` means "fewer than `level`
price levels on this side", distinct from a legitimate price of zero.

## Stack

- Kotlin 2.3.21 (JVM target 25)
- Java 25 toolchain
- JUnit Jupiter 6.1
- Hamcrest 3
- Gradle 9.6.0

The core is a pure data-structure-and-algorithms exercise; a thin JDK-`HttpServer` front end (no web framework, no DB) wraps the matching engine for the live order book above.

## License

Apache 2.0 — see [LICENSE](LICENSE).
