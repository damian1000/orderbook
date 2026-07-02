package io.github.damian1000.orderbook.book

import io.github.damian1000.orderbook.model.Order
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import java.util.ArrayDeque

/**
 * Array-indexed alternative to [PlainOrderBook]: price levels are addressed directly by tick
 * offset from [referencePrice] into a pre-sized array of [levels] slots, instead of a
 * [java.util.TreeMap] lookup — O(1) best-price access at the cost of a bounded, fixed-at-
 * construction price band instead of an unbounded, sparse one. See the README's Design decisions
 * and Complexity sections for the full trade-off. No concurrency control, like [PlainOrderBook] —
 * callers serialise access.
 */
class TickArrayOrderBook(
    private val referencePrice: Price,
    private val tickSize: Long,
    private val levels: Int,
) : OrderBook {
    init {
        require(tickSize > 0) { "tickSize must be positive, got $tickSize" }
        require(levels > 0) { "levels must be positive, got $levels" }
    }

    private val bidLevels = Array(levels) { ArrayDeque<Order>() }
    private val offerLevels = Array(levels) { ArrayDeque<Order>() }
    private val ordersMap: MutableMap<Long, Order> = HashMap()

    // Highest populated bid index / lowest populated offer index, maintained incrementally rather
    // than searched — this is what makes best-price access O(1). NO_LEVEL means that side is empty.
    private var bestBidIndex = NO_LEVEL
    private var bestOfferIndex = NO_LEVEL
    private var bidPopulated = 0
    private var offerPopulated = 0

    override fun addOrder(order: Order) {
        ordersMap[order.id]?.let { removeOrderFromBook(it) }
        val index = indexOf(order.price)
        val level = levelsFor(order.side)[index]
        val wasEmpty = level.isEmpty()
        level.addLast(order)
        ordersMap[order.id] = order
        if (wasEmpty) onLevelPopulated(order.side, index)
    }

    override fun removeOrder(orderId: Long): Boolean {
        val removed = ordersMap.remove(orderId) ?: return false
        removeOrderFromBook(removed)
        return true
    }

    override fun modifyOrder(
        orderId: Long,
        size: Long,
    ): Boolean {
        // Same in-place mutation as PlainOrderBook: ordersMap and the level deque share the
        // Order instance, so the size change is O(1) and never touches its queue position.
        val order = ordersMap[orderId] ?: return false
        order.size = size
        return true
    }

    override fun getPrice(
        side: Side,
        level: Int,
    ): Price? {
        requireValidLevel(level)
        val index = indexAtLevel(side, level) ?: return null
        return priceAt(index)
    }

    override fun getTotalSize(
        side: Side,
        level: Int,
    ): Long {
        requireValidLevel(level)
        val index = indexAtLevel(side, level) ?: return 0
        return levelsFor(side)[index].sumOf { it.size }
    }

    override fun getOrders(side: Side): List<Order> {
        val result = ArrayList<Order>()
        eachPopulatedIndex(side) { index -> levelsFor(side)[index].mapTo(result) { it.snapshot() } }
        return result
    }

    override fun bestResting(side: Side): Order? {
        val index = bestIndex(side)
        if (index == NO_LEVEL) return null
        return levelsFor(side)[index].peekFirst()?.snapshot()
    }

    private fun requireValidLevel(level: Int) {
        require(level > 0) { "level must be positive, got $level" }
    }

    private fun levelsFor(side: Side): Array<ArrayDeque<Order>> =
        when (side) {
            Side.BID -> bidLevels
            Side.OFFER -> offerLevels
        }

    private fun bestIndex(side: Side): Int =
        when (side) {
            Side.BID -> bestBidIndex
            Side.OFFER -> bestOfferIndex
        }

    private fun priceAt(index: Int): Price = Price(referencePrice.ticks + index.toLong() * tickSize)

    private fun indexOf(price: Price): Int {
        val offset = price.ticks - referencePrice.ticks
        require(offset >= 0 && offset % tickSize == 0L) {
            "price $price is not on the $tickSize-tick grid from $referencePrice"
        }
        val index = (offset / tickSize).toInt()
        require(index < levels) { "price $price is outside the configured $levels-level band from $referencePrice" }
        return index
    }

    private fun removeOrderFromBook(order: Order) {
        val index = indexOf(order.price)
        val level = levelsFor(order.side)[index]
        level.removeIf { it.id == order.id }
        if (level.isEmpty()) onLevelEmptied(order.side, index)
    }

    private fun onLevelPopulated(
        side: Side,
        index: Int,
    ) {
        when (side) {
            Side.BID -> {
                bidPopulated++
                if (bestBidIndex == NO_LEVEL || index > bestBidIndex) bestBidIndex = index
            }
            Side.OFFER -> {
                offerPopulated++
                if (bestOfferIndex == NO_LEVEL || index < bestOfferIndex) bestOfferIndex = index
            }
        }
    }

    private fun onLevelEmptied(
        side: Side,
        index: Int,
    ) {
        when (side) {
            Side.BID -> {
                bidPopulated--
                if (index == bestBidIndex) {
                    var i = index - 1
                    while (i >= 0 && bidLevels[i].isEmpty()) i--
                    bestBidIndex = if (i >= 0) i else NO_LEVEL
                }
            }
            Side.OFFER -> {
                offerPopulated--
                if (index == bestOfferIndex) {
                    var i = index + 1
                    while (i < levels && offerLevels[i].isEmpty()) i++
                    bestOfferIndex = if (i < levels) i else NO_LEVEL
                }
            }
        }
    }

    // Walks from the best-price pointer outward, skipping empty slots, to the level-th populated
    // one. O(1) best case (level == 1); worst case O(levels) for a deep level in a sparse band —
    // see the README's Complexity section for the honest trade-off against PlainOrderBook.
    private fun indexAtLevel(
        side: Side,
        level: Int,
    ): Int? {
        val populated = if (side == Side.BID) bidPopulated else offerPopulated
        if (level > populated) return null
        val step = if (side == Side.BID) -1 else 1
        val arr = levelsFor(side)
        var index = bestIndex(side)
        var remaining = level - 1
        while (remaining > 0) {
            index += step
            while (arr[index].isEmpty()) index += step
            remaining--
        }
        return index
    }

    private inline fun eachPopulatedIndex(
        side: Side,
        action: (Int) -> Unit,
    ) {
        val start = bestIndex(side)
        if (start == NO_LEVEL) return
        val arr = levelsFor(side)
        if (side == Side.BID) {
            for (i in start downTo 0) if (arr[i].isNotEmpty()) action(i)
        } else {
            for (i in start until levels) if (arr[i].isNotEmpty()) action(i)
        }
    }

    private companion object {
        const val NO_LEVEL = -1
    }
}
