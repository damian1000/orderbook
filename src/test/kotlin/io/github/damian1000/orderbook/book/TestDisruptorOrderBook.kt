package io.github.damian1000.orderbook.book

import io.github.damian1000.orderbook.model.Order
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TestDisruptorOrderBook : OrderBookContractTest() {
    override fun newOrderBook(): OrderBook = DisruptorOrderBook()

    /**
     * Many threads publish to the ring buffer concurrently (`ProducerType.MULTI`), but the one
     * consumer thread applies every operation serially — so a balanced add-then-remove leaves an
     * empty book with nothing lost or duplicated.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun concurrentSubmissionsAreSerialisedWithoutLoss() {
        DisruptorOrderBook().use { book ->
            val threads = 8
            val ordersPerThread = 2_000
            val executor = Executors.newFixedThreadPool(threads)
            val startGate = CountDownLatch(1)
            val done = CountDownLatch(threads)
            for (t in 0 until threads) {
                executor.submit {
                    startGate.await()
                    val idBase = t.toLong() * ordersPerThread
                    for (i in 0 until ordersPerThread) {
                        book.addOrder(Order(idBase + i, Price.of("100"), Side.BID, 1))
                    }
                    for (i in 0 until ordersPerThread) {
                        book.removeOrder(idBase + i)
                    }
                    done.countDown()
                }
            }
            startGate.countDown()
            assertTrue(done.await(8, TimeUnit.SECONDS), "all submissions must complete")
            executor.shutdown()
            assertTrue(book.getOrders(Side.BID).isEmpty(), "no orders should be lost or duplicated")
        }
    }
}
