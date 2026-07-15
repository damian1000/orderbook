package io.github.damian1000.orderbook.quote

import io.github.damian1000.marketdata.model.Quote
import io.github.damian1000.orderbook.market.SeedLiquidity
import io.github.damian1000.orderbook.market.SeedOrder
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Builds a [SeedLiquidity] ladder around a real [Quote]'s last price: three levels each side,
 * sizes tapering toward the touch — the same shape as [SeedLiquidity.default], just centred on
 * the market instead of a fixed ~100. Offsets are a percentage of price, not a fixed cent amount,
 * so the ladder looks sane whether the instrument trades at $20 or $300.
 */
object QuoteSeed {
    private val OFFSET_PERCENTAGES = listOf(BigDecimal("0.0010"), BigDecimal("0.0025"), BigDecimal("0.0045"))
    private val SIZES = listOf(10L, 7L, 4L)

    fun around(quote: Quote): SeedLiquidity {
        val mid = quote.last
        val orders =
            OFFSET_PERCENTAGES.zip(SIZES).flatMap { (percent, size) ->
                val offset = mid.multiply(percent)
                listOf(
                    SeedOrder(tick(mid + offset), Side.OFFER, size),
                    SeedOrder(tick(mid - offset), Side.BID, size),
                )
            }
        return SeedLiquidity(orders)
    }

    private fun tick(value: BigDecimal): Price = Price.of(value.setScale(2, RoundingMode.HALF_UP).toPlainString())
}
