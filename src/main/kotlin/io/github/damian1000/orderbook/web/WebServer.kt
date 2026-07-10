package io.github.damian1000.orderbook.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.damian1000.orderbook.kafka.KafkaMarketEgress
import io.github.damian1000.orderbook.market.CommandListener
import io.github.damian1000.orderbook.market.DepthListener
import io.github.damian1000.orderbook.market.FillListener
import io.github.damian1000.orderbook.market.Market
import io.github.damian1000.orderbook.market.MarketSession
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * HTTP transport for a [Market]: serves the static UI, the JSON API, and the SSE stream endpoint.
 * Live frames reach the [Broadcaster] from the market's depth stream via [DepthBroadcast] (wired
 * where the session is built — see [main]); this class only attaches clients to it. Plumbing only —
 * book behaviour lives in the market layer, rendering in the view layer. JDK [HttpServer] on a
 * cached pool, which serves the long-lived SSE streams.
 *
 * Reads are GET; `/api/order` mutates the book, so it is POST-only — a GET that changes state
 * would be prefetchable/cacheable. Invalid input maps to a 400 with a JSON `error` body; anything
 * unexpected maps to a 500.
 */
class WebServer(
    private val session: Market,
    private val assets: WebAssets,
    private val broadcaster: Broadcaster,
    private val port: Int,
) {
    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService

    /** Binds and starts serving; requesting port 0 binds an ephemeral port (see [boundPort]). */
    fun start() {
        server = HttpServer.create(InetSocketAddress(port), 0)
        executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        server.executor = executor
        server.createContext("/", ::route)
        broadcaster.startHeartbeat()
        server.start()
        println("Order book server listening on :$boundPort")
    }

    /** The port actually bound — differs from the requested one when 0 (ephemeral) was asked for. */
    val boundPort: Int get() = server.address.port

    /** Stops accepting connections and shuts down the request pool this server created. */
    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun route(exchange: HttpExchange) {
        try {
            when (exchange.requestURI.path) {
                "/healthz" -> get(exchange) { respond(exchange, 200, "text/plain", "ok") }
                "/" -> get(exchange) { respond(exchange, 200, "text/html; charset=utf-8", assets.indexHtml) }
                "/app.css" -> get(exchange) { respond(exchange, 200, "text/css; charset=utf-8", assets.appCss) }
                "/app.js" -> get(exchange) { respond(exchange, 200, "text/javascript; charset=utf-8", assets.appJs) }
                "/api/state" -> get(exchange) { respond(exchange, 200, "application/json", session.snapshot().toJson()) }
                "/api/order" -> post(exchange) { respond(exchange, 200, "application/json", submit(exchange)) }
                "/api/stream" -> get(exchange) { broadcaster.stream(exchange, session.snapshot().toJson()) }
                else -> respond(exchange, 404, "text/plain", "not found")
            }
        } catch (e: IllegalArgumentException) {
            respond(exchange, 400, "application/json", """{"error":${jsonString(e.message ?: "bad request")}}""")
        } catch (e: Exception) {
            // Anything unexpected must still answer the request — without this the connection
            // just closes with no status line. The stack goes to stderr -> journalctl; the
            // response stays generic.
            e.printStackTrace()
            runCatching { respond(exchange, 500, "application/json", """{"error":"internal error"}""") }
        }
    }

    private fun submit(exchange: HttpExchange): String {
        val params = queryParams(exchange.requestURI.rawQuery)
        val side = parseSide(params["side"])
        val price = parsePrice(params["price"])
        val size = parseSize(params["size"])

        // The SSE push is not done here: the market's depth stream broadcasts on the writer
        // thread, so racing submits can't deliver stale frames out of order.
        val outcome = session.submit(side, price, size)
        return """{"matched":${outcome.matched},${outcome.snapshot.toJson().drop(1)}"""
    }

    private fun parseSide(raw: String?): Side =
        when {
            raw.equals("BID", true) || raw.equals("BUY", true) -> Side.BID
            raw.equals("OFFER", true) || raw.equals("SELL", true) -> Side.OFFER
            else -> throw IllegalArgumentException("side must be BID/BUY or OFFER/SELL, got '${raw ?: ""}'")
        }

    // Price.of signals over-precision/overflow with ArithmeticException; remap it so every invalid
    // input reaches the caller as the one exception type route() turns into a 400.
    private fun parsePrice(raw: String?): Price {
        val text = raw ?: throw IllegalArgumentException("price required")
        return try {
            Price.of(text)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("price is not a valid decimal: '$text'")
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("price must fit ${Price.SCALE} decimal places without overflow: '$text'")
        }
    }

    private fun parseSize(raw: String?): Long {
        val text = raw ?: throw IllegalArgumentException("size required")
        return text.toLongOrNull() ?: throw IllegalArgumentException("size is not a valid integer: '$text'")
    }

    private inline fun get(
        exchange: HttpExchange,
        handler: () -> Unit,
    ) = allow(exchange, "GET", handler)

    private inline fun post(
        exchange: HttpExchange,
        handler: () -> Unit,
    ) = allow(exchange, "POST", handler)

    private inline fun allow(
        exchange: HttpExchange,
        method: String,
        handler: () -> Unit,
    ) {
        if (exchange.requestMethod == method) {
            handler()
        } else {
            exchange.responseHeaders.add("Allow", method)
            respond(exchange, 405, "text/plain", "method not allowed")
        }
    }

    private fun queryParams(raw: String?): Map<String, String> =
        (raw ?: "").split("&").filter { it.contains("=") }.associate {
            val (key, value) = it.split("=", limit = 2)
            key to java.net.URLDecoder.decode(value, StandardCharsets.UTF_8)
        }

    private fun jsonString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

fun main() {
    val port = (System.getenv("PORT") ?: "8080").toInt()
    // Kafka egress is opt-in by environment: unset means no producer exists at all and the
    // server runs exactly as before. The fills topic is the seam downstream consumers read;
    // the commands topic is the ordered log a fresh book can be replayed from; the L2 topic
    // carries the latest depth (each record supersedes the last — compact it broker-side).
    val egress =
        System.getenv("KAFKA_BOOTSTRAP_SERVERS")?.let { bootstrap ->
            KafkaMarketEgress.create(
                bootstrapServers = bootstrap,
                fillsTopic = System.getenv("KAFKA_FILLS_TOPIC") ?: KafkaMarketEgress.DEFAULT_FILLS_TOPIC,
                commandsTopic = System.getenv("KAFKA_COMMANDS_TOPIC") ?: KafkaMarketEgress.DEFAULT_COMMANDS_TOPIC,
                l2Topic = System.getenv("KAFKA_L2_TOPIC") ?: KafkaMarketEgress.DEFAULT_L2_TOPIC,
                symbol = System.getenv("ORDERBOOK_SYMBOL") ?: KafkaMarketEgress.DEFAULT_SYMBOL,
            )
        }
    val broadcaster = SseBroadcaster()
    val session =
        MarketSession(
            fills = egress ?: FillListener.NONE,
            commands = egress ?: CommandListener.NONE,
            // SSE frames leave from the depth stream on the writer thread — see DepthBroadcast.
            depth = DepthListener.tee(egress ?: DepthListener.NONE, DepthBroadcast(broadcaster)),
        )
    val server = WebServer(session, WebAssets.load(), broadcaster, port)
    // main owns what it wires: on SIGTERM (systemd stop/restart) the server stops accepting,
    // then the broadcaster's heartbeat and the session's writer thread are released, and the
    // egress flushes what it holds before the producer closes.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop()
            broadcaster.close()
            session.close()
            egress?.close()
        },
    )
    server.start()
}
