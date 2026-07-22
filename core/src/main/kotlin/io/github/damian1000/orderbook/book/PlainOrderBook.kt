package io.github.damian1000.orderbook.book

import io.github.damian1000.orderbook.model.Order
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import java.util.ArrayDeque
import java.util.Comparator
import java.util.NavigableMap
import java.util.TreeMap

/**
 * The order-book data structure and algorithms, with no concurrency control — callers serialise
 * access. [LockingOrderBook] and [SingleWriterOrderBook] wrap it, so they differ only in how they
 * synchronise (what the JMH head-to-head isolates).
 */
class PlainOrderBook : OrderBook {
    // Per-price queues are ArrayDeques: contiguous storage (cache-friendly, no node-per-element
    // allocation), addLast = arrival order = time priority, and the matcher only ever takes the head.
    private val buyOrders: NavigableMap<Price, ArrayDeque<Order>> = TreeMap(Comparator.reverseOrder())
    private val sellOrders: NavigableMap<Price, ArrayDeque<Order>> = TreeMap()
    private val ordersMap: MutableMap<Long, Order> = HashMap()

    /** Resting orders across both sides. O(1) — the id map holds exactly the live orders. */
    val size: Int get() = ordersMap.size

    override fun addOrder(order: Order) {
        val orders = ordersForSide(order.side)
        ordersMap[order.id]?.let { removeOrderFromBook(it) }
        orders.computeIfAbsent(order.price) { ArrayDeque() }.addLast(order)
        ordersMap[order.id] = order
    }

    override fun modifyOrder(
        orderId: Long,
        size: Long,
    ): Boolean {
        // ordersMap and the price queue hold the *same* Order instance, so mutating its remaining
        // size in place updates both and keeps it at its queue position (time priority). O(1).
        val order = ordersMap[orderId] ?: return false
        order.size = size
        return true
    }

    override fun removeOrder(orderId: Long): Boolean {
        val removed = ordersMap.remove(orderId) ?: return false
        removeOrderFromBook(removed)
        return true
    }

    override fun getPrice(
        side: Side,
        level: Int,
    ): Price? {
        requireValidLevel(level)
        return getPrice(ordersForSide(side), level)
    }

    override fun getTotalSize(
        side: Side,
        level: Int,
    ): Long {
        requireValidLevel(level)
        return getTotalSize(ordersForSide(side), level)
    }

    override fun getOrders(side: Side): List<Order> = ordersForSide(side).values.flatMap { level -> level.map { it.snapshot() } }

    override fun bestResting(side: Side): Order? =
        ordersForSide(side)
            .firstEntry()
            ?.value
            ?.peekFirst()
            ?.snapshot()

    private fun requireValidLevel(level: Int) {
        require(level > 0) { "level must be positive, got $level" }
    }

    private fun ordersForSide(side: Side): NavigableMap<Price, ArrayDeque<Order>> =
        when (side) {
            Side.BID -> buyOrders
            Side.OFFER -> sellOrders
        }

    private fun removeOrderFromBook(order: Order) {
        val orders = ordersForSide(order.side)
        val ordersAtPrice = orders[order.price] ?: return
        ordersAtPrice.removeIf { it.id == order.id }
        if (ordersAtPrice.isEmpty()) {
            orders.remove(order.price)
        }
    }

    private fun getPrice(
        orders: NavigableMap<Price, ArrayDeque<Order>>,
        level: Int,
    ): Price? {
        if (level > orders.size) return null
        if (level == 1) return orders.firstKey()
        val orderItr = orders.keys.iterator()
        for (i in 0 until level - 1) {
            orderItr.next()
        }
        return orderItr.next()
    }

    private fun getTotalSize(
        orders: NavigableMap<Price, ArrayDeque<Order>>,
        level: Int,
    ): Long {
        if (level > orders.size) return 0
        val orderItr = orders.values.iterator()
        for (i in 0 until level - 1) {
            orderItr.next()
        }
        return orderItr.next().sumOf { it.size }
    }
}
