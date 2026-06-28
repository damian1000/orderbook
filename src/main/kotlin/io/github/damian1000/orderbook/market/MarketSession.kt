package io.github.damian1000.orderbook.market

import io.github.damian1000.orderbook.MatchingEngine
import io.github.damian1000.orderbook.Order
import io.github.damian1000.orderbook.PlainOrderBook
import io.github.damian1000.orderbook.Price
import io.github.damian1000.orderbook.Side
import io.github.damian1000.orderbook.view.MarketSnapshot
import io.github.damian1000.orderbook.view.TapeEntry
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** The result of submitting an order: how many resting orders it filled, and the resulting book. */
data class SubmitOutcome(
    val matched: Int,
    val snapshot: MarketSnapshot,
)

/**
 * A live, shared trading session over a [PlainOrderBook] driven by a [MatchingEngine].
 *
 * The book is not thread-safe, so every read and mutation is serialised onto one owning thread — the
 * single-writer principle — which makes the session safe to share across many concurrent callers
 * with no locks in the data structure itself. It keeps a bounded trade tape and, after each order,
 * replenishes any side swept clean from the injected [SeedLiquidity].
 *
 * It holds no transport concern (HTTP, SSE, JSON), so it is unit-tested directly.
 */
class MarketSession(
    private val seed: SeedLiquidity = SeedLiquidity.default(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val tapeLimit: Int = 30,
) : AutoCloseable {
    private val book = PlainOrderBook()
    private val engine = MatchingEngine(book)
    private val nextId = AtomicLong(1000)
    private val tape = ArrayDeque<TapeEntry>()
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "market-session").apply { isDaemon = true } }

    init {
        onWriter { place(seed.orders) }
    }

    /** Submits a limit order, returning the fills it generated and the resulting [MarketSnapshot]. */
    fun submit(
        side: Side,
        price: Price,
        size: Long,
    ): SubmitOutcome =
        onWriter {
            val now = clock()
            val trades = engine.submit(Order(nextId.getAndIncrement(), price, side, size))
            trades.forEach { trade ->
                tape.addFirst(TapeEntry(trade.price, trade.size, trade.incomingSide, now))
                if (tape.size > tapeLimit) tape.removeLast()
            }
            replenishEmptySides()
            SubmitOutcome(trades.size, snapshotAt(now))
        }

    /** The current state of the book and tape. */
    fun snapshot(): MarketSnapshot = onWriter { snapshotAt(clock()) }

    override fun close() {
        writer.shutdownNow()
    }

    private fun snapshotAt(now: Long): MarketSnapshot =
        MarketSnapshot.of(book.getOrders(Side.BID), book.getOrders(Side.OFFER), tape.toList(), now)

    private fun replenishEmptySides() {
        if (book.getOrders(Side.OFFER).isEmpty()) place(seed.forSide(Side.OFFER))
        if (book.getOrders(Side.BID).isEmpty()) place(seed.forSide(Side.BID))
    }

    private fun place(orders: List<SeedOrder>) =
        orders.forEach { book.addOrder(Order(nextId.getAndIncrement(), it.price, it.side, it.size)) }

    private fun <T> onWriter(block: () -> T): T = writer.submit(block).get()
}
