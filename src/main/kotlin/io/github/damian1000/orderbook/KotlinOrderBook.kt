package io.github.damian1000.orderbook

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Lock-based order book: guards a [PlainOrderBook] with a
 * [ReentrantReadWriteLock]. Mutations take the write lock; queries take the read
 * lock, so reads can proceed concurrently with each other.
 *
 * This is the baseline for the JMH head-to-head against [SingleWriterOrderBook].
 * Note the workload is write-heavy — only the three query methods read — so the
 * read/write split buys little over a plain lock here.
 */
class KotlinOrderBook(
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
}
