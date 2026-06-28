package io.github.damian1000.orderbook.book

import io.github.damian1000.orderbook.model.Order
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Single-writer book: one thread owns a [PlainOrderBook] and runs every op serially, so the data
 * structures need no locks — the cost is a task hand-off per op (the single-writer principle, à la
 * LMAX Disruptor; a lock-free `TreeMap` isn't achievable). Owns a thread, so [close] must be called.
 */
class SingleWriterOrderBook(
    private val delegate: PlainOrderBook = PlainOrderBook(),
) : OrderBook,
    AutoCloseable {
    private val writer: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "orderbook-single-writer").apply { isDaemon = true }
        }

    override fun addOrder(order: Order) = onWriter { delegate.addOrder(order) }

    override fun removeOrder(orderId: Long): Boolean = onWriter { delegate.removeOrder(orderId) }

    override fun modifyOrder(
        orderId: Long,
        size: Long,
    ): Boolean = onWriter { delegate.modifyOrder(orderId, size) }

    override fun getPrice(
        side: Side,
        level: Int,
    ): Price? = onWriter { delegate.getPrice(side, level) }

    override fun getTotalSize(
        side: Side,
        level: Int,
    ): Long = onWriter { delegate.getTotalSize(side, level) }

    override fun getOrders(side: Side): List<Order> = onWriter { delegate.getOrders(side) }

    override fun bestResting(side: Side): Order? = onWriter { delegate.bestResting(side) }

    override fun close() = writer.shutdown()

    // Unwrap so callers see the delegate's own exception, not a wrapped ExecutionException.
    private fun <R> onWriter(task: Callable<R>): R =
        try {
            writer.submit(task).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
}
