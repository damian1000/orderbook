# Kotlin Order Book

[![CI](https://github.com/damian1000/kotlin-orderbook/actions/workflows/ci.yml/badge.svg)](https://github.com/damian1000/kotlin-orderbook/actions/workflows/ci.yml)
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

- **`buyOrders`**: `ConcurrentSkipListMap<Double, LinkedList<Order>>` with reverse natural ordering — highest bid first.
- **`sellOrders`**: `ConcurrentSkipListMap<Double, LinkedList<Order>>` with natural ordering — lowest offer first.
- **`ordersMap`**: `ConcurrentHashMap<Long, Order>` for O(1) lookup by id (needed for remove / modify).
- Per-price queues are `LinkedList<Order>` so insertion order = time priority.
- Mutations to `ordersMap` use `compute` to keep id-lookup and per-price-queue updates consistent under contention.
- **Time priority preserved on modify**: replacing an order in its `LinkedList` preserves its position — *not* by moving it to the tail. (See `updateOrder` — same `LinkedList<Order>.add(newOrder)` keeps the FIFO unless the order is removed first.)

## Complexity

| Operation | Cost | Notes |
|---|---|---|
| `addOrder` | **O(log P)** | `P` = distinct price levels on that side; `LinkedList.add` is O(1) |
| `removeOrder` | **O(log P + N_p)** | id-lookup O(1); `LinkedList.remove(Order)` is O(N) at that price level |
| `modifyOrder` | **O(log P + N_p)** | same as remove + add |
| `getPrice(side, level)` | **O(level)** | iterates the SkipListMap keys until `level` |
| `getTotalSize(side, level)` | **O(level + N_p)** | sums the LinkedList at that level |
| `getOrders(side)` | **O(P + N)** | walks all per-price queues |

The remove/modify cost could be O(log P) instead of O(log P + N_p) by tracking each order's node in its `LinkedList` (or replacing with an indexed map). Trade-off is more bookkeeping for a rarely-hot path.

## Concurrency

- Reads (`getPrice`, `getTotalSize`, `getOrders`) are lock-free against the concurrent maps but **don't see consistent snapshots** across multiple calls.
- Writes (`addOrder`, `modifyOrder`, `removeOrder`) are individually atomic through `ConcurrentHashMap.compute`. Cross-collection invariants (id-map vs. price-map) hold per-operation, not across them.
- This is fine for the spec; a production engine would either pin a single writer per side or use a more sophisticated transaction structure.

## Run

```bash
./gradlew test     # all 16 tests, deterministic (TestSimpleOrderBook)
./gradlew jmh      # JMH micro-benchmarks (~8 min on JDK 25)
```

## Benchmarks

JMH 1.36, JDK 25.0.1, single-threaded, 3×10s warmup + 5×10s measurement, average time per op. Book pre-populated with 10,000 orders across 50 price levels.

| Operation | Avg time | 99.9% CI |
|---|---:|---|
| `getPrice` (best bid / offer, level 1) | **4 ns** | ±1 ns |
| `modifyOrder` (existing id, same price) | **120 ns** | ±10 ns |
| `getTotalSize` at level 5 | **150 ns** | ±2 ns |
| `addOrder` (random side / price) | **347 ns** | ±29 ns |
| `addOrder` + `removeOrder` pair | **546 ns** | ±62 ns |

Reading the table:
- **Best-price lookup is essentially free** — `ConcurrentSkipListMap.firstEntry()` is O(1).
- **add ≈ 350 ns** is dominated by skip-list insertion + a `ConcurrentHashMap.compute`.
- **remove implied** (`addThenRemove − addOrder`) ≈ **200 ns**. The `LinkedList.remove(Order)` is O(N) at the price level, but with the level loadings here it's not the bottleneck.
- Numbers are single-threaded by design — this is a correctness-first design (concurrent maps + per-op atomicity), not a single-writer low-latency engine. A production matching engine would pin one writer per side and use intrusive linked lists for O(1) cancel.

Run on your own hardware:

```bash
./gradlew jmh
cat build/reports/jmh/results.json   # full JSON output
```

Tweak iterations/warmup in `build.gradle` under the `jmh { ... }` block.

## Use it

```kotlin
val book: OrderBook = SimpleOrderBook()

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
