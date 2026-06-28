package io.github.damian1000.orderbook.engine

import io.github.damian1000.orderbook.book.OrderBook
import io.github.damian1000.orderbook.model.Order
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import io.github.damian1000.orderbook.model.Trade

/** A matching strategy: crosses an incoming order against resting liquidity and returns the fills. */
interface Matcher {
    fun submit(order: Order): List<Trade>
}

/**
 * Price-time priority over any [OrderBook]: an order crosses the best opposite levels first,
 * oldest-first within a level, printing each [Trade] at the resting price (so price improvement
 * accrues to the taker); the unfilled remainder rests. Drives only the public book contract, never
 * its internals, so the data structure stays an independently benchmarkable component. Not thread-safe.
 */
class MatchingEngine(
    private val book: OrderBook,
) : Matcher {
    override fun submit(order: Order): List<Trade> {
        val trades = mutableListOf<Trade>()
        var remaining = order.size
        val opposite = order.side.opposite()

        while (remaining > 0) {
            // O(log P) peek at the top of book — no full-side list materialised per fill iteration.
            val best = book.bestResting(opposite) ?: break
            if (!crosses(order.side, order.price, best.price)) break

            val fill = minOf(remaining, best.size)
            trades += Trade(best.price, fill, best.id, order.id, order.side)
            remaining -= fill

            if (fill == best.size) {
                book.removeOrder(best.id)
            } else {
                book.modifyOrder(best.id, best.size - fill)
            }
        }

        if (remaining > 0) {
            book.addOrder(Order(order.id, order.price, order.side, remaining))
        }
        return trades
    }

    private fun crosses(
        incomingSide: Side,
        incomingPrice: Price,
        restingPrice: Price,
    ): Boolean =
        when (incomingSide) {
            Side.BID -> incomingPrice >= restingPrice // a buy crosses asks at or below its limit
            Side.OFFER -> incomingPrice <= restingPrice // a sell crosses bids at or above its limit
        }

    private fun Side.opposite(): Side =
        when (this) {
            Side.BID -> Side.OFFER
            Side.OFFER -> Side.BID
        }
}
