package io.github.damian1000.orderbook.quote

import io.github.damian1000.marketdata.model.Instrument
import io.github.damian1000.marketdata.model.Quote
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class QuoteSeedTest {
    private fun quote(last: String) =
        Quote(
            instrument = Instrument("AAPL", "Apple Inc.", "USD", "NasdaqGS"),
            last = BigDecimal(last),
            previousClose = BigDecimal(last),
            dayHigh = BigDecimal(last),
            dayLow = BigDecimal(last),
            asOf = Instant.parse("2026-07-15T20:00:00Z"),
            marketOpen = true,
        )

    @Test
    fun `builds three levels each side, centred on the quote's last price`() {
        val seed = QuoteSeed.around(quote("100.00"))

        assertEquals(
            listOf(Price.of("100.10"), Price.of("100.25"), Price.of("100.45")),
            seed.forSide(Side.OFFER).map { it.price },
        )
        assertEquals(
            listOf(Price.of("99.90"), Price.of("99.75"), Price.of("99.55")),
            seed.forSide(Side.BID).map { it.price },
        )
    }

    @Test
    fun `sizes taper toward the touch, same on both sides`() {
        val seed = QuoteSeed.around(quote("100.00"))

        assertEquals(listOf(10L, 7L, 4L), seed.forSide(Side.OFFER).map { it.size })
        assertEquals(listOf(10L, 7L, 4L), seed.forSide(Side.BID).map { it.size })
    }

    @Test
    fun `scales proportionally to price - a low-priced instrument gets a tighter ladder`() {
        val seed = QuoteSeed.around(quote("20.00"))

        assertEquals(
            listOf(Price.of("20.02"), Price.of("20.05"), Price.of("20.09")),
            seed.forSide(Side.OFFER).map { it.price },
        )
        assertEquals(
            listOf(Price.of("19.98"), Price.of("19.95"), Price.of("19.91")),
            seed.forSide(Side.BID).map { it.price },
        )
    }

    @Test
    fun `every offer sits above every bid, whatever the price`() {
        val seed = QuoteSeed.around(quote("33.33"))
        val bestBid = seed.forSide(Side.BID).maxOf { it.price }
        val bestOffer = seed.forSide(Side.OFFER).minOf { it.price }

        assertTrue(bestBid < bestOffer, "best bid $bestBid must be below best offer $bestOffer")
    }
}
