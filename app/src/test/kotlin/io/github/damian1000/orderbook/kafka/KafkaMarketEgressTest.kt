package io.github.damian1000.orderbook.kafka

import io.github.damian1000.orderbook.market.SubmitCommand
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import io.github.damian1000.orderbook.model.Trade
import io.github.damian1000.orderbook.view.DepthLevel
import io.github.damian1000.orderbook.view.MarketSnapshot
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KafkaMarketEgressTest {
    private val trade =
        Trade(
            price = Price.of("101.00"),
            size = 5,
            restingOrderId = 7,
            incomingOrderId = 9,
            incomingSide = Side.BID,
        )
    private val command = SubmitCommand(Side.BID, Price.of("101.00"), 5, 1_000L)
    private val snapshot =
        MarketSnapshot(
            timeMillis = 1_000L,
            bids = listOf(DepthLevel(Price.of("99.00"), 10, 10)),
            asks = listOf(DepthLevel(Price.of("101.00"), 5, 5)),
            tape = emptyList(),
        )

    private fun producer(autoComplete: Boolean = true) = MockProducer(autoComplete, null, StringSerializer(), StringSerializer())

    @Test
    fun `publishes a fill as versioned JSON keyed by symbol on the fills topic`() {
        val producer = producer()
        val egress = KafkaMarketEgress(producer)
        egress.fill("SIM", trade, 1_000L)
        egress.close()

        val record = producer.history().single()
        assertEquals("orderbook.fills", record.topic())
        assertEquals("SIM", record.key())
        assertEquals(
            """{"v":1,"symbol":"SIM","price":"101.00000000","size":5,""" +
                """"makerOrderId":7,"takerOrderId":9,"aggressor":"BID","ts":1000}""",
            record.value(),
        )
        assertEquals(1, egress.published)
    }

    @Test
    fun `publishes an accepted command as versioned JSON on the commands topic`() {
        val producer = producer()
        val egress = KafkaMarketEgress(producer)
        egress.submit("SIM", command)
        egress.close()

        val record = producer.history().single()
        assertEquals("orderbook.commands", record.topic())
        assertEquals("SIM", record.key())
        assertEquals(
            """{"v":1,"symbol":"SIM","side":"BID","price":"101.00000000","size":5,"ts":1000}""",
            record.value(),
        )
    }

    @Test
    fun `publishes the latest depth as the view layer's book JSON in the egress envelope`() {
        val producer = producer()
        val egress = KafkaMarketEgress(producer)
        egress.depth("SIM", snapshot)
        egress.close()

        val record = producer.history().single()
        assertEquals("orderbook.l2", record.topic())
        assertEquals("SIM", record.key())
        assertEquals(
            """{"v":1,"symbol":"SIM","ts":1000,"bids":[{"price":"99.00000000","size":10,"cumulative":10}],""" +
                """"asks":[{"price":"101.00000000","size":5,"cumulative":5}]}""",
            record.value(),
        )
    }

    @Test
    fun `each symbol's records are keyed with its own symbol, not a shared default`() {
        val producer = producer()
        val egress = KafkaMarketEgress(producer)
        egress.fill("AAPL", trade, 1_000L)
        egress.fill("MSFT", trade, 1_000L)
        egress.close()

        assertEquals(listOf("AAPL", "MSFT"), producer.history().map { it.key() })
    }

    @Test
    fun `forSymbol hands a MarketSession the plain listener seams tagged with one symbol`() {
        val producer = producer()
        val egress = KafkaMarketEgress(producer)
        val symbolEgress = egress.forSymbol("AAPL")
        symbolEgress.onFill(trade, 1_000L)
        symbolEgress.onSubmit(command)
        symbolEgress.onDepth(snapshot)
        egress.close()

        assertTrue(producer.history().all { it.key() == "AAPL" }, "every record should carry the bound symbol")
    }

    @Test
    fun `events interleave onto their own topics in submission order`() {
        val producer = producer()
        val egress = KafkaMarketEgress(producer)
        egress.submit("SIM", command)
        egress.fill("SIM", trade, 1_000L)
        egress.depth("SIM", snapshot)
        egress.submit("SIM", command.copy(size = 3, timeMillis = 2_000L))
        egress.close()

        assertEquals(
            listOf("orderbook.commands", "orderbook.fills", "orderbook.l2", "orderbook.commands"),
            producer.history().map { it.topic() },
        )
        assertEquals(4, egress.published)
    }

    @Test
    fun `the started egress thread drains events off the caller's thread`() {
        val producer = producer()
        val egress = KafkaMarketEgress(producer)
        egress.start()
        egress.fill("SIM", trade, 1_000L)

        val deadline = System.nanoTime() + 5_000_000_000L
        while (producer.history().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(1, producer.history().size, "the egress thread should have drained the fill")
        egress.close()
    }

    @Test
    fun `a full queue drops the oldest event and counts it`() {
        val producer = producer()
        // Not started: nothing drains, so capacity 1 forces the drop path deterministically.
        val egress = KafkaMarketEgress(producer, queueCapacity = 1)
        egress.fill("SIM", trade, 1L)
        egress.fill("SIM", trade.copy(incomingOrderId = 10), 2L)
        egress.fill("SIM", trade.copy(incomingOrderId = 11), 3L)

        assertEquals(2, egress.dropped, "two older fills should have been shed")
        egress.close()
        val survivor = producer.history().single()
        assertTrue(survivor.value().contains(""""takerOrderId":11"""), "the newest fill survives, got ${survivor.value()}")
    }

    @Test
    fun `a failed send is counted, never thrown`() {
        val producer = producer(autoComplete = false)
        val egress = KafkaMarketEgress(producer)
        egress.fill("SIM", trade, 1L)
        egress.close()

        assertTrue(producer.errorNext(RuntimeException("broker down")), "a send should be pending")
        assertEquals(1, egress.failed)
        assertEquals(0, egress.published)
    }

    @Test
    fun `create builds and closes a real producer without a reachable broker`() {
        // No broker listens here; construction is lazy, and close() must still return promptly.
        val egress = KafkaMarketEgress.create("localhost:59099")
        egress.close()
        assertEquals(0, egress.published)
    }

    @Test
    fun `create with credentials builds and closes a real SASL producer without a reachable broker`() {
        val egress = KafkaMarketEgress.create("localhost:59099", scram = ScramCredentials("user", "pw"))
        egress.close()
        assertEquals(0, egress.published)
    }

    @Test
    fun `without credentials the producer config carries no security settings`() {
        val props = KafkaMarketEgress.producerProperties("localhost:9092", scram = null)
        assertEquals(null, props["security.protocol"])
        assertEquals(null, props["sasl.mechanism"])
        assertEquals(null, props["sasl.jaas.config"])
    }

    @Test
    fun `with credentials the producer authenticates over SASL_PLAINTEXT with SCRAM-SHA-256`() {
        val props = KafkaMarketEgress.producerProperties("localhost:9092", ScramCredentials("orderbook-egress", "s3cret"))
        assertEquals("SASL_PLAINTEXT", props["security.protocol"])
        assertEquals("SCRAM-SHA-256", props["sasl.mechanism"])
        assertEquals(
            """org.apache.kafka.common.security.scram.ScramLoginModule required """ +
                """username="orderbook-egress" password="s3cret";""",
            props["sasl.jaas.config"],
        )
    }

    @Test
    fun `JAAS values escape quotes and backslashes`() {
        val props = KafkaMarketEgress.producerProperties("localhost:9092", ScramCredentials("user", """p"w\d"""))
        assertEquals(
            """org.apache.kafka.common.security.scram.ScramLoginModule required """ +
                """username="user" password="p\"w\\d";""",
            props["sasl.jaas.config"],
        )
    }
}
