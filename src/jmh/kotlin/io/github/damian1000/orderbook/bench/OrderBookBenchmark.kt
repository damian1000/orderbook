package io.github.damian1000.orderbook.bench

import io.github.damian1000.orderbook.KotlinOrderBook
import io.github.damian1000.orderbook.Order
import io.github.damian1000.orderbook.Side
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
import kotlin.random.Random

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class OrderBookBenchmark {
    @Param("10000")
    var prepopulated: Int = 0

    @Param("50")
    var priceLevels: Int = 0

    private lateinit var book: KotlinOrderBook
    private val nextId = AtomicLong()
    private lateinit var knownIds: LongArray

    @Setup(Level.Iteration)
    fun setup() {
        book = KotlinOrderBook()
        nextId.set(0)
        val rng = Random(42)
        knownIds = LongArray(prepopulated)
        for (i in 0 until prepopulated) {
            val side = if (i % 2 == 0) Side.BID else Side.OFFER
            val offset = rng.nextInt(priceLevels)
            val price = if (side == Side.BID) 100.0 - offset else 100.0 + offset
            val id = nextId.incrementAndGet()
            knownIds[i] = id
            book.addOrder(Order(id, price, side, 100L))
        }
    }

    private fun nextSide(id: Long): Side = if (id and 1L == 0L) Side.BID else Side.OFFER

    private fun priceFor(
        side: Side,
        id: Long,
    ): Double {
        val offset = (id % priceLevels).toInt()
        return if (side == Side.BID) 100.0 - offset else 100.0 + offset
    }

    // Non-stationary: book grows unboundedly inside each iteration's measurement window,
    // so the reported average mixes operation cost at many different book sizes. Kept for
    // diagnostic comparison only — use addThenRemove for the headline number.
    @Benchmark
    fun addOrder(bh: Blackhole) {
        val id = nextId.incrementAndGet()
        val side = nextSide(id)
        book.addOrder(Order(id, priceFor(side, id), side, 100L))
        bh.consume(id)
    }

    @Benchmark
    fun addThenRemove() {
        val id = nextId.incrementAndGet()
        val side = nextSide(id)
        book.addOrder(Order(id, priceFor(side, id), side, 100L))
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
