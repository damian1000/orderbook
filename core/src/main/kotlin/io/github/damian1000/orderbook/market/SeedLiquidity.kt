package io.github.damian1000.orderbook.market

import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side

/** A resting order used to seed/replenish a side of the book. */
data class SeedOrder(
    val price: Price,
    val side: Side,
    val size: Long,
)

/**
 * The resting liquidity a [MarketSession] opens with and tops a swept side up from, so the shared
 * book never looks empty. An explicit injected value, not data baked into the session.
 */
data class SeedLiquidity(
    val orders: List<SeedOrder>,
) {
    fun forSide(side: Side): List<SeedOrder> = orders.filter { it.side == side }

    companion object {
        fun default(): SeedLiquidity =
            SeedLiquidity(
                listOf(
                    SeedOrder(Price.of("102.00"), Side.OFFER, 12),
                    SeedOrder(Price.of("101.50"), Side.OFFER, 8),
                    SeedOrder(Price.of("101.00"), Side.OFFER, 5),
                    SeedOrder(Price.of("99.50"), Side.BID, 6),
                    SeedOrder(Price.of("99.00"), Side.BID, 10),
                    SeedOrder(Price.of("98.00"), Side.BID, 15),
                ),
            )
    }
}
