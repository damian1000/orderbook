package io.github.damian1000.orderbook.book

import io.github.damian1000.orderbook.model.Order
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Lock-based book: guards a [PlainOrderBook] with a read/write lock (reads concurrent, writes
 * exclusive). The JMH baseline against [SingleWriterOrderBook].
 */
class LockingOrderBook(
    private val delegate: PlainOrderBook = PlainOrderBook(),
) : OrderBook {
    private val lock = ReentrantReadWriteLock()

    override fun addOrder(order: Order) = lock.write { delegate.addOrder(order) }

    override fun removeOrder(orderId: Long): Boolean = lock.write { delegate.removeOrder(orderId) }

    override fun modifyOrder(
        orderId: Long,
        size: Long,
    ): Boolean = lock.write { delegate.modifyOrder(orderId, size) }

    override fun getPrice(
        side: Side,
        level: Int,
    ): Price? = lock.read { delegate.getPrice(side, level) }

    override fun getTotalSize(
        side: Side,
        level: Int,
    ): Long = lock.read { delegate.getTotalSize(side, level) }

    override fun getOrders(side: Side): List<Order> = lock.read { delegate.getOrders(side) }

    override fun bestResting(side: Side): Order? = lock.read { delegate.bestResting(side) }
}
