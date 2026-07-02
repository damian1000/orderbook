package io.github.damian1000.orderbook.bench

import io.github.damian1000.orderbook.book.DisruptorOrderBook
import io.github.damian1000.orderbook.book.LockingOrderBook
import io.github.damian1000.orderbook.book.OrderBook
import io.github.damian1000.orderbook.book.SingleWriterOrderBook
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
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class OrderBookBenchmark {
    @Param("10000")
    var prepopulated: Int = 0

    @Param("50")
    var priceLevels: Int = 0

    @Param("lock", "single-writer", "disruptor")
    var impl: String = ""

    private lateinit var book: OrderBook
    private val nextId = AtomicLong()
    private lateinit var knownIds: LongArray

    @Setup(Level.Iteration)
    fun setup() {
        book =
            when (impl) {
                "lock" -> LockingOrderBook()
                "single-writer" -> SingleWriterOrderBook()
                "disruptor" -> DisruptorOrderBook()
                else -> error("unknown impl: $impl")
            }
        nextId.set(0)
        val rng = Random(42)
        knownIds = LongArray(prepopulated)
        for (i in 0 until prepopulated) {
            val side = if (i % 2 == 0) Side.BID else Side.OFFER
            val offset = rng.nextInt(priceLevels)
            val whole = if (side == Side.BID) 100L - offset else 100L + offset
            val id = nextId.incrementAndGet()
            knownIds[i] = id
            book.addOrder(Order(id, Price(whole * UNIT), side, 100L))
        }
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        (book as? AutoCloseable)?.close()
    }

    // Non-stationary: book grows unboundedly inside each iteration's measurement window,
    // so the reported average mixes operation cost at many different book sizes. Kept for
    // diagnostic comparison only — use addThenRemove for the headline number.
    @Benchmark
    fun addOrder(bh: Blackhole) {
        val id = nextId.incrementAndGet()
        val side = nextSide(id)
        book.addOrder(Order(id, priceFor(side, id, priceLevels), side, 100L))
        bh.consume(id)
    }

    @Benchmark
    fun addThenRemove() {
        val id = nextId.incrementAndGet()
        val side = nextSide(id)
        book.addOrder(Order(id, priceFor(side, id, priceLevels), side, 100L))
        book.removeOrder(id)
    }

    @Benchmark
    fun modifyExisting() {
        val id = knownIds[(nextId.incrementAndGet() % knownIds.size).toInt()]
        book.modifyOrder(id, 200L)
    }

    @Benchmark
    fun getBestBid(bh: Blackhole) {
        bh.consume(book.getPrice(Side.BID, 1))
    }

    @Benchmark
    fun getBestOffer(bh: Blackhole) {
        bh.consume(book.getPrice(Side.OFFER, 1))
    }

    @Benchmark
    fun getTotalSizeLevel5(bh: Blackhole) {
        bh.consume(book.getTotalSize(Side.BID, 5))
    }
}
