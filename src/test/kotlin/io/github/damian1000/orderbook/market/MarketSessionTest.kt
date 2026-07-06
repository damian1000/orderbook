package io.github.damian1000.orderbook.market

import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import io.github.damian1000.orderbook.model.Trade
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarketSessionTest {
    private val seed =
        SeedLiquidity(
            listOf(
                SeedOrder(Price.of("101.00"), Side.OFFER, 5),
                SeedOrder(Price.of("99.00"), Side.BID, 5),
            ),
        )

    private fun session() = MarketSession(seed = seed, clock = { 1_000L })

    @Test
    fun `opens with the seeded liquidity on both sides`() {
        session().use { session ->
            val snapshot = session.snapshot()
            assertEquals(listOf(Price.of("99.00")), snapshot.bids.map { it.price })
            assertEquals(listOf(Price.of("101.00")), snapshot.asks.map { it.price })
            assertTrue(snapshot.tape.isEmpty())
        }
    }

    @Test
    fun `a crossing order fills resting liquidity and prints to the tape`() {
        session().use { session ->
            val outcome = session.submit(Side.BID, Price.of("101.00"), 5)

            assertEquals(1, outcome.matched)
            assertEquals(1, outcome.snapshot.tape.size)
            val trade = outcome.snapshot.tape.first()
            assertEquals(Price.of("101.00"), trade.price)
            assertEquals(Side.BID, trade.incomingSide)
            assertEquals(1_000L, trade.timeMillis)
        }
    }

    @Test
    fun `a non-crossing order rests on the book without matching`() {
        session().use { session ->
            val outcome = session.submit(Side.BID, Price.of("98.00"), 3)

            assertEquals(0, outcome.matched)
            assertTrue(outcome.snapshot.tape.isEmpty())
            assertEquals(listOf(Price.of("99.00"), Price.of("98.00")), outcome.snapshot.bids.map { it.price })
        }
    }

    @Test
    fun `a swept side is replenished so the book never goes one-sided`() {
        session().use { session ->
            // The lone seeded ask is 5 at 101.00; buying it all empties the offer side.
            val outcome = session.submit(Side.BID, Price.of("101.00"), 5)

            assertTrue(outcome.snapshot.asks.isNotEmpty(), "offer side should be replenished from the seed")
            assertEquals(listOf(Price.of("101.00")), outcome.snapshot.asks.map { it.price })
        }
    }

    @Test
    fun `an invalid order surfaces the validation error itself, not a wrapped ExecutionException`() {
        session().use { session ->
            val thrown =
                assertThrows(IllegalArgumentException::class.java) {
                    session.submit(Side.BID, Price.of("100.00"), 0)
                }
            assertTrue(thrown.message!!.contains("size"), "expected Order's own message, got: ${thrown.message}")
        }
    }

    @Test
    fun `every fill reaches the fill listener with the tape timestamp`() {
        val fills = mutableListOf<Pair<Trade, Long>>()
        val listener = FillListener { trade, ts -> fills.add(trade to ts) }
        MarketSession(seed = seed, clock = { 1_000L }, fills = listener).use { session ->
            session.submit(Side.BID, Price.of("101.00"), 5)
            session.submit(Side.BID, Price.of("98.00"), 3)
        }

        val (trade, ts) = fills.single()
        assertEquals(Price.of("101.00"), trade.price)
        assertEquals(5L, trade.size)
        assertEquals(Side.BID, trade.incomingSide)
        assertEquals(1_000L, ts)
    }

    @Test
    fun `accepted submits reach the command listener in order, rejected ones never do`() {
        val log = mutableListOf<SubmitCommand>()
        MarketSession(seed = seed, clock = { 1_000L }, commands = { log.add(it) }).use { session ->
            session.submit(Side.BID, Price.of("101.00"), 5)
            assertThrows(IllegalArgumentException::class.java) {
                session.submit(Side.BID, Price.of("100.00"), 0)
            }
            session.submit(Side.OFFER, Price.of("102.00"), 3)
        }

        assertEquals(
            listOf(
                SubmitCommand(Side.BID, Price.of("101.00"), 5, 1_000L),
                SubmitCommand(Side.OFFER, Price.of("102.00"), 3, 1_000L),
            ),
            log,
        )
    }

    @Test
    fun `the tape is bounded by the configured limit`() {
        MarketSession(seed = seed, clock = { 1_000L }, tapeLimit = 2).use { session ->
            repeat(4) { session.submit(Side.BID, Price.of("101.00"), 5) }
            assertEquals(2, session.snapshot().tape.size)
        }
    }
}
