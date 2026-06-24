package io.github.damian1000.orderbook

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Single-writer order book: a private [PlainOrderBook] is owned by exactly one
 * thread, and every operation — read or write — is submitted to that thread and
 * run serially. Because only the writer thread ever touches the data structures,
 * they need no locks; the cost moves from lock contention to a task hand-off per
 * operation.
 *
 * This is the practical "lockless" design used by low-latency engines (the
 * single-writer principle, à la LMAX Disruptor) rather than a lock-free data
 * structure, which isn't achievable over a [java.util.TreeMap]. Benchmarked head
 * to head with [KotlinOrderBook]: the lock is expected to win uncontended (no
 * hand-off), while single-writer should degrade more gracefully as writer
 * contention rises, since there is no lock cache-line to bounce between cores.
 *
 * Owns a thread, so it is [AutoCloseable] — [close] stops the writer and the
 * instance must not be used afterwards.
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

    override fun close() = writer.shutdown()

    // Runs the task on the writer thread and waits for its result. Unwraps the
    // ExecutionException so callers see the delegate's own exception (e.g. the
    // IllegalArgumentException from an invalid level), not a wrapped one.
    private fun <R> onWriter(task: Callable<R>): R =
        try {
            writer.submit(task).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
}
