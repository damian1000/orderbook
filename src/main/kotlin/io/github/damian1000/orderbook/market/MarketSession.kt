package io.github.damian1000.orderbook.market

import io.github.damian1000.orderbook.book.PlainOrderBook
import io.github.damian1000.orderbook.engine.Matcher
import io.github.damian1000.orderbook.engine.MatchingEngine
import io.github.damian1000.orderbook.model.Order
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import io.github.damian1000.orderbook.view.MarketSnapshot
import io.github.damian1000.orderbook.view.TapeEntry
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Fills produced by an order, plus the resulting book. */
data class SubmitOutcome(
    val matched: Int,
    val snapshot: MarketSnapshot,
)

/** A live market: accept orders, expose the book. The seam the web layer depends on, not the impl. */
interface Market {
    fun submit(
        side: Side,
        price: Price,
        size: Long,
    ): SubmitOutcome

    fun snapshot(): MarketSnapshot
}

/**
 * The default [Market]: a shared session over a [PlainOrderBook] + [MatchingEngine].
 *
 * The book isn't thread-safe, so every read and mutation runs on one owning thread (single-writer),
 * making it safe to share without locks. Keeps a bounded tape and replenishes a swept side so the
 * shared book never looks empty.
 */
class MarketSession(
    private val seed: SeedLiquidity = SeedLiquidity.default(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val tapeLimit: Int = 30,
    private val fills: FillListener = FillListener.NONE,
    private val commands: CommandListener = CommandListener.NONE,
    private val depth: DepthListener = DepthListener.NONE,
) : Market,
    AutoCloseable {
    private val book = PlainOrderBook()
    private val engine: Matcher = MatchingEngine(book)
    private val nextId = AtomicLong(1000)
    private val tape = ArrayDeque<TapeEntry>()
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "market-session").apply { isDaemon = true } }

    init {
        onWriter {
            place(seed.orders)
            depth.onDepth(snapshotAt(clock()))
        }
    }

    override fun submit(
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
                fills.onFill(trade, now)
            }
            replenishEmptySides()
            // Logged only once the submit has been applied: a rejected order threw above and
            // never reaches the command log, so a replayed log contains no failing submits.
            commands.onSubmit(SubmitCommand(side, price, size, now))
            val snapshot = snapshotAt(now)
            depth.onDepth(snapshot)
            SubmitOutcome(trades.size, snapshot)
        }

    override fun snapshot(): MarketSnapshot = onWriter { snapshotAt(clock()) }

    override fun close() {
        writer.shutdownNow()
    }

    private fun snapshotAt(now: Long): MarketSnapshot =
        MarketSnapshot.of(book.getOrders(Side.BID), book.getOrders(Side.OFFER), tape.toList(), now)

    private fun replenishEmptySides() {
        if (book.bestResting(Side.OFFER) == null) place(seed.forSide(Side.OFFER))
        if (book.bestResting(Side.BID) == null) place(seed.forSide(Side.BID))
    }

    private fun place(orders: List<SeedOrder>) =
        orders.forEach { book.addOrder(Order(nextId.getAndIncrement(), it.price, it.side, it.size)) }

    // Unwrap so callers see the block's own exception (e.g. Order's size validation), not a
    // wrapped ExecutionException the web layer's error mapping can't recognise.
    private fun <T> onWriter(block: () -> T): T =
        try {
            writer.submit(block).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
}
