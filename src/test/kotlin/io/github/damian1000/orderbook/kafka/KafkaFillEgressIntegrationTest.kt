package io.github.damian1000.orderbook.kafka

import io.github.damian1000.orderbook.market.MarketSession
import io.github.damian1000.orderbook.market.SeedLiquidity
import io.github.damian1000.orderbook.market.SeedOrder
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.Properties

/**
 * The egress against a real broker: fills produced by a live [MarketSession] must arrive on the
 * fills topic, keyed and shaped as documented. A stub cannot fail the way a broker fails
 * (metadata, partitioning, acks), so this test runs against the real thing; it skips only where
 * Docker is absent and always runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
class KafkaFillEgressIntegrationTest {
    companion object {
        @Container
        @JvmField
        val kafka: KafkaContainer = KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
    }

    private val seed =
        SeedLiquidity(
            listOf(
                SeedOrder(Price.of("101.00"), Side.OFFER, 5),
                SeedOrder(Price.of("99.00"), Side.BID, 5),
            ),
        )

    @Test
    fun `fills from a live session arrive on the fills topic`() {
        val publisher = KafkaFillPublisher.create(kafka.bootstrapServers)
        MarketSession(seed = seed, clock = { 1_000L }, fills = publisher).use { session ->
            session.submit(Side.BID, Price.of("101.00"), 5)
        }
        // The first send also waits out topic auto-creation, which can take seconds on a fresh
        // broker — wait for the ack rather than racing it.
        val ackDeadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (publisher.published == 0L && publisher.failed == 0L && System.nanoTime() < ackDeadline) {
            Thread.sleep(100)
        }
        publisher.close()
        assertEquals(1, publisher.published, "the broker should have acknowledged the fill, failed=${publisher.failed}")

        val records = consumeAll()
        val record = records.single()
        assertEquals("SIM", record.key())
        assertTrue(record.value().contains(""""price":"101.00000000""""), record.value())
        assertTrue(record.value().contains(""""size":5"""), record.value())
        assertTrue(record.value().contains(""""aggressor":"BID""""), record.value())
        assertTrue(record.value().contains(""""ts":1000"""), record.value())
    }

    private fun consumeAll(): List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> {
        val props =
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, "egress-integration-test")
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            }
        KafkaConsumer(props, StringDeserializer(), StringDeserializer()).use { consumer ->
            consumer.subscribe(listOf(KafkaFillPublisher.DEFAULT_TOPIC))
            val collected = mutableListOf<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>()
            val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
            while (collected.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach { collected.add(it) }
            }
            return collected
        }
    }
}
