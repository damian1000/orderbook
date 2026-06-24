package io.github.damian1000.orderbook.bench

import io.github.damian1000.orderbook.KotlinOrderBook
import io.github.damian1000.orderbook.Order
import io.github.damian1000.orderbook.OrderBook
import io.github.damian1000.orderbook.Side
import io.github.damian1000.orderbook.SingleWriterOrderBook
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
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Multi-threaded, contended head-to-head between the lock-based [KotlinOrderBook]
 * and the [SingleWriterOrderBook]. Throughput (ops/ms, higher is better) with a
 * shared book hammered by [Threads] worker threads — this is the regime the
 * single-threaded [OrderBookBenchmark] cannot show.
 *
 * Expectations the numbers test:
 * - **writeHeavy**: writes serialise either way; the question is whether the
 *   lock's contention overhead costs more than the single-writer hand-off.
 * - **readHeavy**: the read/write lock lets reads run concurrently, so the lock
 *   should scale where the single writer (which serialises everything) cannot.
 * - **mixed**: mostly reads with occasional writes — closest to a real feed.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Threads(8)
open class ContendedOrderBookBenchmark {
    @Param("lock", "single-writer")
    var impl: String = ""

    @Param("10000")
    var prepopulated: Int = 0

    @Param("50")
    var priceLevels: Int = 0

    private lateinit var book: OrderBook
    private val nextId = AtomicLong()

    @Setup(Level.Iteration)
    fun setup() {
        book =
            when (impl) {
                "lock" -> KotlinOrderBook()
                "single-writer" -> SingleWriterOrderBook()
                else -> error("unknown impl: $impl")
            }
        nextId.set(prepopulated.toLong())
        for (i in 0 until prepopulated) {
            val id = i.toLong()
            val side = nextSide(id)
            book.addOrder(Order(id, priceFor(side, id, priceLevels), side, 100L))
        }
    }

    @TearDown(Level.Iteration)
    fun tearDown() {
        (book as? AutoCloseable)?.close()
    }

    @Benchmark
    fun writeHeavy() {
        val id = nextId.incrementAndGet()
        val side = nextSide(id)
        book.addOrder(Order(id, priceFor(side, id, priceLevels), side, 100L))
        book.removeOrder(id)
    }

    @Benchmark
    fun readHeavy(bh: Blackhole) {
        bh.consume(book.getPrice(Side.BID, 1))
        bh.consume(book.getPrice(Side.OFFER, 1))
    }

    @Benchmark
    fun mixed(bh: Blackhole) {
        val id = nextId.incrementAndGet()
        // ~10% writes, 90% reads — closest to a live market-data feed.
        if (id % 10L == 0L) {
            val side = nextSide(id)
            book.addOrder(Order(id, priceFor(side, id, priceLevels), side, 100L))
            book.removeOrder(id)
        } else {
            bh.consume(book.getPrice(Side.BID, 1))
        }
    }
}
