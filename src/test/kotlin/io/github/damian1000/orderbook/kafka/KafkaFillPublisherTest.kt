package io.github.damian1000.orderbook.kafka

import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import io.github.damian1000.orderbook.model.Trade
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KafkaFillPublisherTest {
    private val trade =
        Trade(
            price = Price.of("101.00"),
            size = 5,
            restingOrderId = 7,
            incomingOrderId = 9,
            incomingSide = Side.BID,
        )

    private fun producer(autoComplete: Boolean = true) = MockProducer(autoComplete, null, StringSerializer(), StringSerializer())

    @Test
    fun `publishes a fill as versioned JSON keyed by symbol`() {
        val producer = producer()
        val publisher = KafkaFillPublisher(producer)
        publisher.onFill(trade, 1_000L)
        publisher.close()

        val record = producer.history().single()
        assertEquals("orderbook.fills", record.topic())
        assertEquals("SIM", record.key())
        assertEquals(
            """{"v":1,"symbol":"SIM","price":"101.00000000","size":5,""" +
                """"makerOrderId":7,"takerOrderId":9,"aggressor":"BID","ts":1000}""",
            record.value(),
        )
        assertEquals(1, publisher.published)
    }

    @Test
    fun `the started egress thread drains fills off the caller's thread`() {
        val producer = producer()
        val publisher = KafkaFillPublisher(producer)
        publisher.start()
        publisher.onFill(trade, 1_000L)

        val deadline = System.nanoTime() + 5_000_000_000L
        while (producer.history().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(1, producer.history().size, "the egress thread should have drained the fill")
        publisher.close()
    }

    @Test
    fun `a full queue drops the oldest fill and counts it`() {
        val producer = producer()
        // Not started: nothing drains, so capacity 1 forces the drop path deterministically.
        val publisher = KafkaFillPublisher(producer, queueCapacity = 1)
        publisher.onFill(trade, 1L)
        publisher.onFill(trade.copy(incomingOrderId = 10), 2L)
        publisher.onFill(trade.copy(incomingOrderId = 11), 3L)

        assertEquals(2, publisher.dropped, "two older fills should have been shed")
        publisher.close()
        val survivor = producer.history().single()
        assertTrue(survivor.value().contains(""""takerOrderId":11"""), "the newest fill survives, got ${survivor.value()}")
    }

    @Test
    fun `a failed send is counted, never thrown`() {
        val producer = producer(autoComplete = false)
        val publisher = KafkaFillPublisher(producer)
        publisher.onFill(trade, 1L)
        publisher.close()

        assertTrue(producer.errorNext(RuntimeException("broker down")), "a send should be pending")
        assertEquals(1, publisher.failed)
        assertEquals(0, publisher.published)
    }

    @Test
    fun `create builds and closes a real producer without a reachable broker`() {
        // No broker listens here; construction is lazy, and close() must still return promptly.
        val publisher = KafkaFillPublisher.create("localhost:59099")
        publisher.close()
        assertEquals(0, publisher.published)
    }
}
