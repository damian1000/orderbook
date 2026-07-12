package io.github.damian1000.orderbook.kafka

import io.github.damian1000.orderbook.market.CommandListener
import io.github.damian1000.orderbook.market.DepthListener
import io.github.damian1000.orderbook.market.FillListener
import io.github.damian1000.orderbook.market.SubmitCommand
import io.github.damian1000.orderbook.model.Trade
import io.github.damian1000.orderbook.view.MarketSnapshot
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Kafka egress for the market's event stream: fills on one topic, the accepted-command log on
 * a second, and the book's latest depth on a third. Never on the match path — the listener callbacks run on the market's writer thread
 * and only enqueue; a dedicated egress thread drains the queue into the [Producer]. When the
 * queue is full — broker down, network slow — the oldest pending event is dropped and counted
 * rather than blocking the writer: the live book must not degrade because the egress is sick.
 *
 * Records are versioned JSON keyed by symbol, so per-symbol ordering holds once multiple
 * instruments exist. Delivery is at-least-once while the broker is reachable; the [dropped]
 * counter is the honest record of any gap. A gap matters differently per topic: the tape is a
 * rebuildable view and each depth snapshot supersedes the last, but a command log with a hole
 * no longer replays to the live book's state — the counter is what says whether a log is
 * replayable.
 */
class KafkaMarketEgress(
    private val producer: Producer<String, String>,
    private val fillsTopic: String = DEFAULT_FILLS_TOPIC,
    private val commandsTopic: String = DEFAULT_COMMANDS_TOPIC,
    private val l2Topic: String = DEFAULT_L2_TOPIC,
    private val symbol: String = DEFAULT_SYMBOL,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) : FillListener,
    CommandListener,
    DepthListener,
    AutoCloseable {
    private sealed interface Pending

    private data class PendingFill(
        val trade: Trade,
        val timeMillis: Long,
    ) : Pending

    private data class PendingCommand(
        val command: SubmitCommand,
    ) : Pending

    private data class PendingDepth(
        val snapshot: MarketSnapshot,
    ) : Pending

    private val queue = ArrayBlockingQueue<Pending>(queueCapacity)
    private val egress = Thread(::drain, "kafka-market-egress").apply { isDaemon = true }
    private val droppedCount = AtomicLong()
    private val publishedCount = AtomicLong()
    private val failedCount = AtomicLong()

    @Volatile
    private var running = false

    /** Events dropped because the queue was full (egress slower than the market). */
    val dropped: Long get() = droppedCount.get()

    /** Records acknowledged by the broker, across both topics. */
    val published: Long get() = publishedCount.get()

    /** Sends that completed with an error (broker unreachable, timeout). */
    val failed: Long get() = failedCount.get()

    /** Starts the egress thread. Separate from construction so tests can exercise a stopped queue. */
    fun start() {
        running = true
        egress.start()
    }

    override fun onFill(
        trade: Trade,
        timeMillis: Long,
    ) = enqueue(PendingFill(trade, timeMillis))

    override fun onSubmit(command: SubmitCommand) = enqueue(PendingCommand(command))

    override fun onDepth(snapshot: MarketSnapshot) = enqueue(PendingDepth(snapshot))

    private fun enqueue(event: Pending) {
        // Drop-oldest, never block: shedding the stalest event keeps the stream current and the
        // writer thread unblocked. The loop resolves the race with a concurrent drain.
        while (!queue.offer(event)) {
            if (queue.poll() != null) droppedCount.incrementAndGet()
        }
    }

    /**
     * Stops the egress thread, flushes whatever is still queued, and closes the producer.
     * Never interrupts: an interrupt landing inside `producer.send()` (e.g. its first-send
     * metadata fetch) kills the send and loses the event — the drain loop notices [running]
     * within its poll timeout instead, and `producer.close` waits for in-flight acks.
     */
    override fun close() {
        running = false
        egress.join(CLOSE_TIMEOUT.toMillis())
        while (true) send(queue.poll() ?: break)
        producer.close(CLOSE_TIMEOUT)
    }

    private fun drain() {
        try {
            while (running) {
                send(queue.poll(100, TimeUnit.MILLISECONDS) ?: continue)
            }
        } catch (_: InterruptedException) {
            // Nothing interrupts this thread on purpose; treat a stray interrupt as shutdown.
        }
    }

    private fun send(event: Pending) {
        val record =
            when (event) {
                is PendingFill -> ProducerRecord(fillsTopic, symbol, fillJson(event))
                is PendingCommand -> ProducerRecord(commandsTopic, symbol, commandJson(event.command))
                is PendingDepth -> ProducerRecord(l2Topic, symbol, depthJson(event.snapshot))
            }
        try {
            producer.send(record) { _, exception ->
                if (exception == null) publishedCount.incrementAndGet() else failedCount.incrementAndGet()
            }
        } catch (_: RuntimeException) {
            // send() can also fail synchronously (metadata timeout, serialization); a broken
            // egress must count the loss and keep draining, never die silently.
            failedCount.incrementAndGet()
        }
    }

    private fun fillJson(fill: PendingFill): String {
        val trade = fill.trade
        return """{"v":1,"symbol":${quote(symbol)},"price":${quote(trade.price.toString())},"size":${trade.size},""" +
            """"makerOrderId":${trade.restingOrderId},"takerOrderId":${trade.incomingOrderId},""" +
            """"aggressor":${quote(trade.incomingSide.name)},"ts":${fill.timeMillis}}"""
    }

    private fun commandJson(command: SubmitCommand): String =
        """{"v":1,"symbol":${quote(symbol)},"side":${quote(command.side.name)},""" +
            """"price":${quote(command.price.toString())},"size":${command.size},"ts":${command.timeMillis}}"""

    // The book body comes from the view layer's serialisation; the egress adds only its envelope.
    private fun depthJson(snapshot: MarketSnapshot): String = """{"v":1,"symbol":${quote(symbol)},""" + snapshot.depthJson().drop(1)

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        const val DEFAULT_FILLS_TOPIC = "orderbook.fills"
        const val DEFAULT_COMMANDS_TOPIC = "orderbook.commands"
        const val DEFAULT_L2_TOPIC = "orderbook.l2"
        const val DEFAULT_SYMBOL = "SIM"
        const val DEFAULT_QUEUE_CAPACITY = 4096
        private val CLOSE_TIMEOUT = Duration.ofSeconds(5)

        /** A started egress over a real [KafkaProducer]. The timeouts are tightened from the
         * defaults so a dead broker surfaces as counted failures and drops within seconds instead
         * of buffering silently for two minutes. With [scram] the producer authenticates over
         * SASL_PLAINTEXT/SCRAM-SHA-256; without it the connection is unauthenticated plaintext. */
        fun create(
            bootstrapServers: String,
            fillsTopic: String = DEFAULT_FILLS_TOPIC,
            commandsTopic: String = DEFAULT_COMMANDS_TOPIC,
            l2Topic: String = DEFAULT_L2_TOPIC,
            symbol: String = DEFAULT_SYMBOL,
            scram: ScramCredentials? = null,
        ): KafkaMarketEgress {
            val producer = KafkaProducer(producerProperties(bootstrapServers, scram), StringSerializer(), StringSerializer())
            return KafkaMarketEgress(producer, fillsTopic, commandsTopic, l2Topic, symbol).also { it.start() }
        }

        internal fun producerProperties(
            bootstrapServers: String,
            scram: ScramCredentials?,
        ): Properties =
            Properties().apply {
                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(ProducerConfig.CLIENT_ID_CONFIG, "orderbook-egress")
                put(ProducerConfig.LINGER_MS_CONFIG, 5)
                put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000)
                put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000)
                put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000)
                // 8 MB, not the 32 MB default — the producer lives inside a 256 MB live-server heap.
                put(ProducerConfig.BUFFER_MEMORY_CONFIG, 8L * 1024 * 1024)
                if (scram != null) {
                    put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT")
                    put(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-256")
                    put(
                        SaslConfigs.SASL_JAAS_CONFIG,
                        "org.apache.kafka.common.security.scram.ScramLoginModule required " +
                            "username=${jaasQuote(scram.username)} password=${jaasQuote(scram.password)};",
                    )
                }
            }

        // JAAS values are double-quoted strings; escape the two characters that break out of one.
        private fun jaasQuote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
