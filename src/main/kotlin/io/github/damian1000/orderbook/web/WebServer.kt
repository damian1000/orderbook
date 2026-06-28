package io.github.damian1000.orderbook.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.damian1000.orderbook.market.Market
import io.github.damian1000.orderbook.market.MarketSession
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * HTTP transport for a [Market]: serves the static UI, the JSON API, and live SSE updates via a
 * [Broadcaster]. Plumbing only — book behaviour lives in the market layer, rendering in the view
 * layer. JDK [HttpServer] on a cached pool, which serves the long-lived SSE streams.
 */
class WebServer(
    private val session: Market,
    private val assets: WebAssets,
    private val broadcaster: Broadcaster,
    private val port: Int,
) {
    fun start() {
        val server = HttpServer.create(InetSocketAddress(port), 0)
        server.executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        server.createContext("/", ::route)
        broadcaster.startHeartbeat()
        server.start()
        println("Order book server listening on :$port")
    }

    private fun route(exchange: HttpExchange) {
        try {
            when (exchange.requestURI.path) {
                "/" -> respond(exchange, 200, "text/html; charset=utf-8", assets.indexHtml)
                "/app.css" -> respond(exchange, 200, "text/css; charset=utf-8", assets.appCss)
                "/app.js" -> respond(exchange, 200, "text/javascript; charset=utf-8", assets.appJs)
                "/api/state" -> respond(exchange, 200, "application/json", session.snapshot().toJson())
                "/api/order" -> respond(exchange, 200, "application/json", submit(exchange))
                "/api/stream" -> broadcaster.stream(exchange, session.snapshot().toJson())
                else -> respond(exchange, 404, "text/plain", "not found")
            }
        } catch (e: IllegalArgumentException) {
            respond(exchange, 400, "application/json", """{"error":${jsonString(e.message ?: "bad request")}}""")
        }
    }

    private fun submit(exchange: HttpExchange): String {
        val params = queryParams(exchange.requestURI.rawQuery)
        val side = parseSide(params["side"])
        val price = Price.of(params["price"] ?: error("price required"))
        val size = (params["size"] ?: error("size required")).toLong()

        val outcome = session.submit(side, price, size)
        val json = outcome.snapshot.toJson()
        broadcaster.broadcast(json)
        return """{"matched":${outcome.matched},${json.drop(1)}"""
    }

    private fun parseSide(raw: String?): Side = if (raw.equals("BID", true) || raw.equals("BUY", true)) Side.BID else Side.OFFER

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
    WebServer(MarketSession(), WebAssets.load(), SseBroadcaster(), port).start()
}
