package io.github.damian1000.orderbook.book

import com.lmax.disruptor.BusySpinWaitStrategy
import com.lmax.disruptor.EventFactory
import com.lmax.disruptor.EventHandler
import com.lmax.disruptor.dsl.Disruptor
import com.lmax.disruptor.dsl.ProducerType
import io.github.damian1000.orderbook.model.Order
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import java.util.concurrent.ThreadFactory

/**
 * Single-writer book backed by an LMAX Disruptor ring buffer instead of [SingleWriterOrderBook]'s
 * `ExecutorService` + `BlockingQueue`. A caller publishes a [Request] to the ring buffer, then
 * busy-spins on it until the one consumer thread has run it — no lock or thread park on either
 * side, unlike the blocking-queue hand-off. Owns a thread, so [close] must be called.
 */
class DisruptorOrderBook(
    private val delegate: PlainOrderBook = PlainOrderBook(),
) : OrderBook,
    AutoCloseable {
    private val disruptor =
        Disruptor(
            EventFactory { Slot() },
            RING_BUFFER_SIZE,
            ThreadFactory { runnable -> Thread(runnable, "orderbook-disruptor-writer").apply { isDaemon = true } },
            ProducerType.MULTI,
            BusySpinWaitStrategy(),
        )

    init {
        disruptor.handleEventsWith(RequestHandler())
        disruptor.start()
    }

    private val ringBuffer = disruptor.ringBuffer

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

    override fun close() = disruptor.shutdown()

    private fun <R> onWriter(task: () -> R): R {
        // Request is allocated fresh per call — it must NOT live in the reused ring-buffer slot.
        // The slot is only a transient handle: as soon as the handler finishes with it, Disruptor
        // permits another producer to reuse that same slot object for an unrelated request, which
        // would race a caller still reading a result out of it. Request has no such reuse, since
        // only this call's thread ever holds a reference to it.
        val request = Request(task)
        val sequence = ringBuffer.next()
        try {
            ringBuffer.get(sequence).request = request
        } finally {
            ringBuffer.publish(sequence)
        }

        while (!request.completed) {
            Thread.onSpinWait()
        }
        request.error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return request.result as R
    }

    private class Request(
        val task: () -> Any?,
    ) {
        var result: Any? = null
        var error: Throwable? = null

        // Set last (after result/error) so its volatile write is the happens-before edge the
        // busy-spinning caller relies on to safely read result/error once it observes true.
        @Volatile
        var completed: Boolean = false
    }

    // The reused ring-buffer slot: just a handle to the per-call Request, never the request state
    // itself.
    private class Slot {
        var request: Request? = null
    }

    private class RequestHandler : EventHandler<Slot> {
        override fun onEvent(
            event: Slot,
            sequence: Long,
            endOfBatch: Boolean,
        ) {
            val request = event.request!!
            event.request = null
            try {
                request.result = request.task.invoke()
            } catch (t: Throwable) {
                request.error = t
            } finally {
                request.completed = true
            }
        }
    }

    private companion object {
        const val RING_BUFFER_SIZE = 1024
    }
}
