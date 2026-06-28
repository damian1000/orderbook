package io.github.damian1000.orderbook.engine

import io.github.damian1000.orderbook.book.OrderBook
import io.github.damian1000.orderbook.model.Order
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import io.github.damian1000.orderbook.model.Trade

/**
 * A matching strategy: takes an incoming order, crosses it against resting liquidity, and returns
 * the resulting fills (any unfilled remainder rests on the book).
 *
 * The interface names the capability independently of the rule used. [MatchingEngine] implements
 * price-time priority; richer strategies (pro-rata, etc.) would be alternative implementations the
 * rest of the system consumes through this same contract.
 */
interface Matcher {
    fun submit(order: Order): List<Trade>
}

/**
 * Price-time-priority matching on top of an [OrderBook].
 *
 * A [submit]ted order crosses the best opposite price levels first, and within a
 * level fills the oldest resting order before moving on (time priority). Each match
 * prints a [Trade] at the resting order's price. Any quantity the order can't fill
 * against crossable liquidity rests on the book as a passive limit order.
 *
 * The matching loop never reaches into the book's internals — it drives the same
 * `add` / `remove` / `modify` / query contract every [OrderBook] exposes — so the
 * data structure stays a clean, independently-benchmarked component and any book
 * implementation (lock-based or single-writer) can be matched on.
 *
 * Not thread-safe: like [PlainOrderBook], callers serialise access.
 *
 * Scope: this matches plain limit orders (cross, then rest the remainder). Richer
 * order types — market, IOC/FOK, stop, iceberg — build on this and are tracked
 * separately. The repeated best-order lookup is `O(orders)` per fill via the public
 * contract; the `O(log P)` book-peek path is a deliberate later optimisation.
 */
class MatchingEngine(
    private val book: OrderBook,
) : Matcher {
    /**
     * Matches [order] against the book, returning the [Trade]s it generated (in
     * execution order). Any unfilled remainder is added to the book as a resting
     * limit order on [order]'s own side.
     */
    override fun submit(order: Order): List<Trade> {
        val trades = mutableListOf<Trade>()
        var remaining = order.size
        val opposite = order.side.opposite()

        while (remaining > 0) {
            // getOrders flattens best-price-first then time-first, so the head is the
            // oldest resting order at the best opposite level.
            val best = book.getOrders(opposite).firstOrNull() ?: break
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

    /**
     * @return true if an incoming order on [incomingSide] priced at [incomingPrice]
     *         crosses a resting order priced at [restingPrice].
     */
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
