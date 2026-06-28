package io.github.damian1000.orderbook.view

import io.github.damian1000.orderbook.model.Order
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side

/** A price level aggregated across every resting order at that price, with the running depth total. */
data class DepthLevel(
    val price: Price,
    val size: Long,
    val cumulative: Long,
)

/** One printed trade, tagged with the side of the incoming (aggressing) order and the time it matched. */
data class TapeEntry(
    val price: Price,
    val size: Long,
    val incomingSide: Side,
    val timeMillis: Long,
)

/**
 * An immutable, render-ready projection of the book: each side aggregated by price (best level
 * first) with cumulative depth, plus the recent trade tape.
 *
 * It is deliberately pure — it takes plain lists, holds no reference to the book, and does no I/O —
 * so the aggregation and JSON serialisation are unit-tested directly rather than living inside the
 * web entry point (which is excluded from coverage). The web layer is then a thin transport over it.
 */
data class MarketSnapshot(
    val timeMillis: Long,
    val bids: List<DepthLevel>,
    val asks: List<DepthLevel>,
    val tape: List<TapeEntry>,
) {
    /** Hand-rolled JSON (no dependency) for the `/api/state`, `/api/order` and SSE payloads. */
    fun toJson(): String {
        val bidsJson = bids.joinToString(",", "[", "]", transform = ::levelJson)
        val asksJson = asks.joinToString(",", "[", "]", transform = ::levelJson)
        val tapeJson = tape.joinToString(",", "[", "]", transform = ::tapeJson)
        return """{"ts":$timeMillis,"bids":$bidsJson,"asks":$asksJson,"tape":$tapeJson}"""
    }

    private fun levelJson(level: DepthLevel): String {
        val price = quote(level.price.toString())
        return """{"price":$price,"size":${level.size},"cumulative":${level.cumulative}}"""
    }

    private fun tapeJson(entry: TapeEntry): String {
        val price = quote(entry.price.toString())
        val side = quote(entry.incomingSide.name)
        return """{"price":$price,"size":${entry.size},"side":$side,"time":${entry.timeMillis}}"""
    }

    companion object {
        /**
         * Builds a snapshot from each side's resting orders — given best level first, the order
         * [io.github.damian1000.orderbook.OrderBook.getOrders] returns — and the current tape.
         */
        fun of(
            bids: List<Order>,
            asks: List<Order>,
            tape: List<TapeEntry>,
            timeMillis: Long,
        ): MarketSnapshot = MarketSnapshot(timeMillis, aggregate(bids), aggregate(asks), tape)

        /** Merges orders sharing a price into a single level (preserving order) and runs the cumulative total. */
        fun aggregate(orders: List<Order>): List<DepthLevel> {
            val byPrice = LinkedHashMap<Price, Long>()
            orders.forEach { byPrice.merge(it.price, it.size, Long::plus) }
            var cumulative = 0L
            return byPrice.map { (price, size) ->
                cumulative += size
                DepthLevel(price, size, cumulative)
            }
        }

        private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
