package io.github.damian1000.orderbook.market

import io.github.damian1000.orderbook.Price
import io.github.damian1000.orderbook.Side

/** A single resting order used to seed — and replenish — a side of the book. */
data class SeedOrder(
    val price: Price,
    val side: Side,
    val size: Long,
)

/**
 * The resting liquidity a [MarketSession] opens with, and tops a side up from when a taker sweeps it
 * clean so a shared public book never appears empty.
 *
 * It is an explicit, injected value — not data hard-coded inside the session — so the session has no
 * opinion on what liquidity to show, and a test (or a different deployment) can supply its own.
 */
data class SeedLiquidity(
    val orders: List<SeedOrder>,
) {
    fun forSide(side: Side): List<SeedOrder> = orders.filter { it.side == side }

    companion object {
        /** The two-sided ladder around 100 the live site opens with. */
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
