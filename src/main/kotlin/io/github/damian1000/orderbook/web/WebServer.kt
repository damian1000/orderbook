package io.github.damian1000.orderbook.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.damian1000.orderbook.market.Market
import io.github.damian1000.orderbook.market.MarketSession
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * HTTP transport for a [Market]: serves the static UI, the JSON API, and live SSE updates via a
 * [Broadcaster]. Plumbing only — book behaviour lives in the market layer, rendering in the view
 * layer. JDK [HttpServer] on a cached pool, which serves the long-lived SSE streams.
 *
 * Reads are GET; `/api/order` mutates the book, so it is POST-only — a GET that changes state
 * would be prefetchable/cacheable. Invalid input maps to a 400 with a JSON `error` body.
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
        }
    }

    private fun submit(exchange: HttpExchange): String {
        val params = queryParams(exchange.requestURI.rawQuery)
        val side = parseSide(params["side"])
        val price = parsePrice(params["price"])
        val size = parseSize(params["size"])

        val outcome = session.submit(side, price, size)
        val json = outcome.snapshot.toJson()
        broadcaster.broadcast(json)
        return """{"matched":${outcome.matched},${json.drop(1)}"""
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
    val session = MarketSession()
    val broadcaster = SseBroadcaster()
    val server = WebServer(session, WebAssets.load(), broadcaster, port)
    // main owns what it wires: on SIGTERM (systemd stop/restart) the server stops accepting,
    // then the broadcaster's heartbeat and the session's writer thread are released.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop()
            broadcaster.close()
            session.close()
        },
    )
    server.start()
}
