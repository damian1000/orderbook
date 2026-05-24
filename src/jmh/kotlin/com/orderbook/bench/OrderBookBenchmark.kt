package com.orderbook.bench

import com.orderbook.Order
import com.orderbook.KotlinOrderBook
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
            val side = if (i % 2 == 0) 'B' else 'O'
            val offset = rng.nextInt(priceLevels)
            val price = if (side == 'B') 100.0 - offset else 100.0 + offset
            val id = nextId.incrementAndGet()
            knownIds[i] = id
            book.addOrder(Order(id, price, side, 100L))
        }
    }

    private fun nextSide(id: Long): Char = if (id and 1L == 0L) 'B' else 'O'

    private fun priceFor(side: Char, id: Long): Double {
        val offset = (id % priceLevels).toInt()
        return if (side == 'B') 100.0 - offset else 100.0 + offset
    }

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
        bh.consume(book.getPrice('B', 1))
    }

    @Benchmark
    fun getBestOffer(bh: Blackhole) {
        bh.consume(book.getPrice('O', 1))
    }

    @Benchmark
    fun getTotalSizeLevel5(bh: Blackhole) {
        bh.consume(book.getTotalSize('B', 5))
    }
}
