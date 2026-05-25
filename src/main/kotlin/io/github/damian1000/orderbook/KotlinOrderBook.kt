package io.github.damian1000.orderbook

import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class KotlinOrderBook : OrderBook {
    private val lock = ReentrantReadWriteLock()
    private val buyOrders: NavigableMap<Double, LinkedList<Order>> = TreeMap(Comparator.reverseOrder())
    private val sellOrders: NavigableMap<Double, LinkedList<Order>> = TreeMap()
    private val ordersMap: MutableMap<Long, Order> = HashMap()

    override fun addOrder(order: Order) {
        lock.write {
            val orders = ordersForSide(order.side)
            ordersMap[order.id]?.let { removeOrderFromBook(it) }
            orders.computeIfAbsent(order.price) { LinkedList() }.add(order)
            ordersMap[order.id] = order
        }
    }

    override fun modifyOrder(orderId: Long, size: Long) {
        lock.write {
            val order = ordersMap[orderId] ?: return
            val ordersAtPrice = ordersForSide(order.side)[order.price] ?: return
            val newOrder = Order(order.id, order.price, order.side, size)
            val iterator = ordersAtPrice.listIterator()
            while (iterator.hasNext()) {
                if (iterator.next().id == orderId) {
                    iterator.set(newOrder)
                    ordersMap[orderId] = newOrder
                    return
                }
            }
        }
    }

    override fun removeOrder(orderId: Long) {
        lock.write {
            ordersMap.remove(orderId)?.let { removeOrderFromBook(it) }
        }
    }

    override fun getPrice(side: Char, level: Int): Double {
        return lock.read {
            getPrice(ordersForSide(side), level)
        }
    }

    override fun getTotalSize(side: Char, level: Int): Long {
        return lock.read {
            getTotalSize(ordersForSide(side), level)
        }
    }

    override fun getOrders(side: Char): List<Order> {
        return lock.read {
            ordersForSide(side).values.flatMap { it.toList() }
        }
    }

    private fun ordersForSide(side: Char): NavigableMap<Double, LinkedList<Order>> {
        return when (side) {
            'B' -> buyOrders
            'O' -> sellOrders
            else -> throw IllegalArgumentException("Unknown side $side")
        }
    }

    private fun removeOrderFromBook(order: Order) {
        val orders = ordersForSide(order.side)
        val ordersAtPrice = orders[order.price] ?: return
        ordersAtPrice.removeIf { it.id == order.id }
        if (ordersAtPrice.isEmpty()) {
            orders.remove(order.price)
        }
    }

    private fun getPrice(orders: NavigableMap<Double, LinkedList<Order>>, level: Int): Double {
        if (level > 0 && level <= orders.size) {
            if (level == 1) {
                return orders.firstKey()
            }
            val orderItr = orders.keys.iterator()
            for (i in 0 until level - 1) {
                orderItr.next()
            }
            return orderItr.next()
        }
        return 0.0
    }

    private fun getTotalSize(orders: NavigableMap<Double, LinkedList<Order>>, level: Int): Long {
        if (level > 0 && level <= orders.size) {
            val orderItr = orders.values.iterator()
            for (i in 0 until level - 1) {
                orderItr.next()
            }
            return orderItr.next().stream().mapToLong { obj: Order -> obj.size }.sum()
        }
        return 0
    }

}
