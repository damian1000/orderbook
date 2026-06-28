package io.github.damian1000.orderbook.book

import io.github.damian1000.orderbook.model.Order
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import java.util.Comparator
import java.util.LinkedList
import java.util.NavigableMap
import java.util.TreeMap

/**
 * The order-book data structure and algorithms, with no concurrency control — callers serialise
 * access. [LockingOrderBook] and [SingleWriterOrderBook] wrap it, so they differ only in how they
 * synchronise (what the JMH head-to-head isolates).
 */
class PlainOrderBook : OrderBook {
    private val buyOrders: NavigableMap<Price, LinkedList<Order>> = TreeMap(Comparator.reverseOrder())
    private val sellOrders: NavigableMap<Price, LinkedList<Order>> = TreeMap()
    private val ordersMap: MutableMap<Long, Order> = HashMap()

    override fun addOrder(order: Order) {
        val orders = ordersForSide(order.side)
        ordersMap[order.id]?.let { removeOrderFromBook(it) }
        orders.computeIfAbsent(order.price) { LinkedList() }.add(order)
        ordersMap[order.id] = order
    }

    override fun modifyOrder(
        orderId: Long,
        size: Long,
    ): Boolean {
        require(size > 0) { "size must be positive, got $size" }
        val order = ordersMap[orderId] ?: return false
        val ordersAtPrice = ordersForSide(order.side)[order.price] ?: return false
        val newOrder = Order(order.id, order.price, order.side, size)
        val iterator = ordersAtPrice.listIterator()
        while (iterator.hasNext()) {
            if (iterator.next().id == orderId) {
                iterator.set(newOrder)
                ordersMap[orderId] = newOrder
                return true
            }
        }
        return false
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

    override fun getOrders(side: Side): List<Order> = ordersForSide(side).values.flatMap { it.toList() }

    private fun requireValidLevel(level: Int) {
        require(level > 0) { "level must be positive, got $level" }
    }

    private fun ordersForSide(side: Side): NavigableMap<Price, LinkedList<Order>> =
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
        orders: NavigableMap<Price, LinkedList<Order>>,
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
        orders: NavigableMap<Price, LinkedList<Order>>,
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
