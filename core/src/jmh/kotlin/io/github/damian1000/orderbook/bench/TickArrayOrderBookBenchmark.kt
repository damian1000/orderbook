package io.github.damian1000.orderbook.bench

import io.github.damian1000.orderbook.book.OrderBook
import io.github.damian1000.orderbook.book.PlainOrderBook
import io.github.damian1000.orderbook.book.TickArrayOrderBook
import io.github.damian1000.orderbook.model.Order
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Head-to-head of [PlainOrderBook]'s `TreeMap` against [TickArrayOrderBook]'s direct indexing,
 * both single-threaded and unwrapped — a data-structure comparison, not a concurrency one, so
 * [io.github.damian1000.orderbook.book.LockingOrderBook] / [io.github.damian1000.orderbook.book.SingleWriterOrderBook]
 * don't belong here (see [OrderBookBenchmark] for that axis). [Mode.SampleTime] reports percentiles
 * because the claim under test is a tail-latency one (O(1) best-price access), not an average.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class TickArrayOrderBookBenchmark {
    @Param("10000")
    var prepopulated: Int = 0

    @Param("50")
    var priceLevels: Int = 0

    @Param("treemap", "array")
    var impl: String = ""

    private lateinit var book: OrderBook
    private val nextId = AtomicLong()

    @Setup(Level.Iteration)
    fun setup() {
        book =
            when (impl) {
                "treemap" -> PlainOrderBook()
                "array" -> TickArrayOrderBook(Price(0L), UNIT, BAND_LEVELS)
                else -> error("unknown impl: $impl")
            }
        nextId.set(prepopulated.toLong())
        for (i in 0 until prepopulated) {
            val side = nextSide(i.toLong())
            book.addOrder(Order(i.toLong(), priceFor(side, i.toLong(), priceLevels), side, RESTING_SIZE))
        }
    }

    @Benchmark
    fun bestRestingBid(bh: Blackhole) {
        bh.consume(book.bestResting(Side.BID))
    }

    @Benchmark
    fun bestRestingOffer(bh: Blackhole) {
        bh.consume(book.bestResting(Side.OFFER))
    }

    // Stationary book size across the measurement window, matching OrderBookBenchmark's convention.
    @Benchmark
    fun addThenRemove() {
        val id = nextId.incrementAndGet()
        val side = nextSide(id)
        book.addOrder(Order(id, priceFor(side, id, priceLevels), side, RESTING_SIZE))
        book.removeOrder(id)
    }

    private companion object {
        // Comfortably covers priceFor's 100 +/- priceLevels range with headroom.
        const val BAND_LEVELS = 1000
    }
}
