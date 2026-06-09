package io.github.damian1000.orderbook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Multi-threaded correctness tests for `KotlinOrderBook`. These verify that
 * the `ReentrantReadWriteLock` discipline holds end-to-end — readers never
 * observe a torn book, writers don't drop orders, and the data structures
 * survive heavy contention without deadlocking.
 *
 * Throughput (operations/second) is the JMH benchmark's job; these tests are
 * about invariants, not performance.
 */
class ConcurrencyStressTest {
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun balancedAddThenRemoveLeavesBookEmpty() {
        val book = KotlinOrderBook()
        val threads = 8
        val ordersPerThread = 5_000
        val executor = Executors.newFixedThreadPool(threads)
        val startGate = CountDownLatch(1)
        val finishGate = CountDownLatch(threads)

        for (t in 0 until threads) {
            executor.submit {
                startGate.await()
                val idBase = t.toLong() * ordersPerThread
                val side = if (t % 2 == 0) Side.BID else Side.OFFER
                for (i in 0 until ordersPerThread) {
                    val price = 100.0 + (i % 50)
                    book.addOrder(Order(idBase + i, price, side, 1))
                }
                for (i in 0 until ordersPerThread) {
                    book.removeOrder(idBase + i)
                }
                finishGate.countDown()
            }
        }
        startGate.countDown()
        assertTrue(finishGate.await(8, TimeUnit.SECONDS), "threads must finish without deadlocking")
        executor.shutdown()

        assertTrue(book.getOrders(Side.BID).isEmpty(), "all bids removed")
        assertTrue(book.getOrders(Side.OFFER).isEmpty(), "all offers removed")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun mixedReadersAndWritersNeverThrowAndPreserveIds() {
        val book = KotlinOrderBook()
        val totalOrders = 2_000
        for (id in 0L until totalOrders) {
            val side = if (id % 2 == 0L) Side.BID else Side.OFFER
            book.addOrder(Order(id, 100.0 + (id % 25), side, 10))
        }

        val writerThreads = 4
        val readerThreads = 4
        val opsPerWriter = 5_000
        val stop = AtomicBoolean(false)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newFixedThreadPool(writerThreads + readerThreads)
        val startGate = CountDownLatch(1)
        val writersDone = CountDownLatch(writerThreads)
        val readersDone = CountDownLatch(readerThreads)

        for (w in 0 until writerThreads) {
            executor.submit {
                runCatching {
                    startGate.await()
                    val rng = ThreadLocalRandom.current()
                    for (i in 0 until opsPerWriter) {
                        val id = rng.nextLong(totalOrders.toLong())
                        when (rng.nextInt(3)) {
                            0 -> {
                                val side = if (rng.nextBoolean()) Side.BID else Side.OFFER
                                book.addOrder(Order(id, 100.0 + rng.nextInt(25), side, rng.nextLong(1, 50)))
                            }
                            1 -> book.modifyOrder(id, rng.nextLong(1, 100))
                            2 -> book.removeOrder(id)
                        }
                    }
                }.onFailure { errors.add(it) }
                writersDone.countDown()
            }
        }
        for (r in 0 until readerThreads) {
            executor.submit {
                runCatching {
                    startGate.await()
                    while (!stop.get()) {
                        book.getOrders(Side.BID)
                        book.getOrders(Side.OFFER)
                        book.getPrice(Side.BID, 1)
                        book.getPrice(Side.OFFER, 1)
                        book.getTotalSize(Side.BID, 1)
                        book.getTotalSize(Side.OFFER, 1)
                    }
                }.onFailure { errors.add(it) }
                readersDone.countDown()
            }
        }

        startGate.countDown()
        assertTrue(writersDone.await(8, TimeUnit.SECONDS), "writers must finish")
        stop.set(true)
        assertTrue(readersDone.await(2, TimeUnit.SECONDS), "readers must finish once stop flag is set")
        executor.shutdown()

        assertTrue(errors.isEmpty(), "no thread should observe an exception, but saw: $errors")

        val bids = book.getOrders(Side.BID).map { it.id }.toSet()
        val offers = book.getOrders(Side.OFFER).map { it.id }.toSet()
        assertTrue(bids.intersect(offers).isEmpty(), "an order cannot be on both sides simultaneously")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun concurrentModifyPreservesOrderCountAndIds() {
        val book = KotlinOrderBook()
        val totalOrders = 500
        for (id in 0L until totalOrders) {
            book.addOrder(Order(id, 100.0, Side.BID, 10))
        }
        val originalIds = (0L until totalOrders).toSet()

        val threads = 6
        val modificationsPerThread = 2_000
        val executor = Executors.newFixedThreadPool(threads)
        val startGate = CountDownLatch(1)
        val done = CountDownLatch(threads)

        for (t in 0 until threads) {
            executor.submit {
                startGate.await()
                val rng = ThreadLocalRandom.current()
                for (i in 0 until modificationsPerThread) {
                    book.modifyOrder(rng.nextLong(totalOrders.toLong()), rng.nextLong(1, 100))
                }
                done.countDown()
            }
        }
        startGate.countDown()
        assertTrue(done.await(8, TimeUnit.SECONDS), "modifications must complete without deadlock")
        executor.shutdown()

        val remainingIds = book.getOrders(Side.BID).map { it.id }.toSet()
        assertEquals(originalIds, remainingIds, "modify must never drop or duplicate orders")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun heavyContentionOnSinglePriceLevelDoesNotDeadlock() {
        val book = KotlinOrderBook()
        val threads = 8
        val opsPerThread = 5_000
        val executor = Executors.newFixedThreadPool(threads)
        val startGate = CountDownLatch(1)
        val done = CountDownLatch(threads)

        for (t in 0 until threads) {
            executor.submit {
                startGate.await()
                val idBase = t.toLong() * opsPerThread
                for (i in 0 until opsPerThread) {
                    book.addOrder(Order(idBase + i, 50.0, Side.BID, 1))
                    book.removeOrder(idBase + i)
                }
                done.countDown()
            }
        }
        startGate.countDown()
        assertTrue(done.await(8, TimeUnit.SECONDS), "single-price-level contention must not deadlock")
        executor.shutdown()
        assertTrue(book.getOrders(Side.BID).isEmpty())
    }
}
