# Kotlin Order Book

[![CI](https://github.com/damian1000/kotlin-orderbook/actions/workflows/ci.yml/badge.svg)](https://github.com/damian1000/kotlin-orderbook/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/damian1000/kotlin-orderbook/graph/badge.svg)](https://codecov.io/gh/damian1000/kotlin-orderbook)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.21-blueviolet)](https://kotlinlang.org/)
[![JDK](https://img.shields.io/badge/jdk-25-orange)](https://openjdk.org/projects/jdk/25/)

A small, thread-safe **limit order book** in Kotlin. Add / modify / remove orders, query the book by side and level, preserving time priority across modifications.

## Problem

Build an `OrderBook` that supports:

1. **Add** an `Order(id, price, side, size)` to the book. Order additions are expected to be the most frequent operation.
2. **Remove** an order by `id`. Removals run at roughly 60% of the add rate.
3. **Modify** the size of an existing order. **Size modifications must not affect time priority.**
4. **Get price** for `(side, level)` — `level=1` returns the best price on that side, `level=2` the second-best, etc.
5. **Get total size** for `(side, level)` — sum of sizes at that price level.
6. **Get orders** for a side, ordered by level then time of arrival.

Sides: `B` = bid (buy), `O` = offer (sell). Best bid = highest price; best offer = lowest price.

## Design

- **`buyOrders`**: `TreeMap<Double, LinkedList<Order>>` with reverse natural ordering — highest bid first.
- **`sellOrders`**: `TreeMap<Double, LinkedList<Order>>` with natural ordering — lowest offer first.
- **`ordersMap`**: `HashMap<Long, Order>` for O(1) lookup by id (needed for remove / modify).
- Per-price queues are `LinkedList<Order>` so insertion order = time priority.
- A `ReentrantReadWriteLock` guards the maps and price queues. Writes are atomic over the full book; reads can run concurrently with other reads.
- **Time priority preserved on modify**: replacing an order in its `LinkedList` preserves its position — it is not moved to the tail.
- Adding an existing id replaces the old visible order first, so duplicate ids do not leave stale orders at old price levels.

## Complexity

| Operation | Cost | Notes |
|---|---|---|
| `addOrder` | **O(log P)** | `P` = distinct price levels on that side; replacing an existing id also removes its old queue entry |
| `removeOrder` | **O(log P + N_p)** | id-lookup O(1); removing from the `LinkedList` is O(N) at that price level |
| `modifyOrder` | **O(log P + N_p)** | finds and replaces the existing queue element in place |
| `getPrice(side, level)` | **O(1)** for level 1, otherwise **O(level)** | uses `TreeMap.firstKey()` for best price; otherwise iterates keys until `level` |
| `getTotalSize(side, level)` | **O(level + N_p)** | sums the LinkedList at that level |
| `getOrders(side)` | **O(P + N)** | walks all per-price queues |

The remove/modify cost could be O(log P) instead of O(log P + N_p) by tracking each order's node in its `LinkedList` (or replacing with an indexed map). Trade-off is more bookkeeping for a rarely-hot path.

## Concurrency

- Reads (`getPrice`, `getTotalSize`, `getOrders`) take a read lock and see a consistent snapshot for that individual call.
- Writes (`addOrder`, `modifyOrder`, `removeOrder`) take a write lock, so id lookup and price-level queues stay consistent.
- This is fine for the spec; a production engine would likely pin a single writer per side or use a more specialized transaction structure.

## Run

```bash
./gradlew test     # all 19 tests, deterministic (TestKotlinOrderBook)
./gradlew jmh      # JMH micro-benchmarks (~8 min on JDK 25)
```

## Benchmarks

JMH 1.36, JDK 25.0.1, single-threaded, 3×10s warmup + 5×10s measurement, average time per op. Book pre-populated with 10,000 orders across 50 price levels.

| Operation | Avg time | 99.9% CI |
|---|---:|---|
| `getPrice` best bid, level 1 | **28 ns** | ±23 ns |
| `getPrice` best offer, level 1 | **22 ns** | ±11 ns |
| `getTotalSize` at level 5 | **216 ns** | ±145 ns |
| `addOrder` (random side / price) | **346 ns** | ±13 ns |
| `modifyOrder` (existing id, same price) | **346 ns** | ±134 ns |
| `addOrder` + `removeOrder` pair | **578 ns** | ±401 ns |

Reading the table:
- **Best-price lookup is still cheap** — `TreeMap.firstKey()` is O(1), with read-lock overhead included.
- **add ≈ 350 ns** is dominated by tree insertion and id-map maintenance.
- **remove implied** (`addThenRemove − addOrder`) ≈ **230 ns**. The `LinkedList.removeIf` is O(N) at the price level, but with the level loadings here it's not the bottleneck.
- **modify ≈ 350 ns** now searches and replaces the existing list element in place, preserving FIFO priority.
- Numbers are single-threaded by design — this is a correctness-first design, not a single-writer low-latency engine. A production matching engine would pin one writer per side and use intrusive linked lists for O(1) cancel.

Run on your own hardware:

```bash
./gradlew jmh
cat build/reports/jmh/results.json   # full JSON output
```

Tweak iterations/warmup in `build.gradle` under the `jmh { ... }` block.

## Use it

```kotlin
val book: OrderBook = KotlinOrderBook()

book.addOrder(Order(id = 1L, price = 19.0, side = 'O', size = 8))
book.addOrder(Order(id = 2L, price = 21.0, side = 'O', size = 16))
book.addOrder(Order(id = 3L, price = 15.0, side = 'B', size = 5))

book.getPrice('O', 1)       // 19.0  — best offer
book.getPrice('B', 1)       // 15.0  — best bid
book.getTotalSize('O', 1)   // 8

book.modifyOrder(orderId = 1L, size = 12)   // size 8 -> 12, time priority preserved
book.removeOrder(orderId = 2L)
```

## Stack

- Kotlin 2.3.21 (JVM target 25)
- Java 25 toolchain
- JUnit Jupiter 6.1
- Hamcrest 3
- Gradle 9.5.1

No web framework, no DB, no other moving parts. Pure data-structure exercise.

## License

Apache 2.0 — see [LICENSE](LICENSE).
