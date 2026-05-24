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
./gradlew test
```

All 16 tests pass deterministically (`TestSimpleOrderBook`).

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
