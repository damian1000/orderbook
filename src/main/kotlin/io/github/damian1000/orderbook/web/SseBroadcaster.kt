package io.github.damian1000.orderbook.web

import com.sun.net.httpserver.HttpExchange
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Server-Sent Events fan-out.
 *
 * Holds the connected browsers and pushes each a copy of every broadcast through its own queue, so a
 * slow client can't block the others. A periodic heartbeat keeps connections alive through proxies
 * and lets a write to a departed client fail, reaping it.
 */
class SseBroadcaster : AutoCloseable {
    private class Client {
        val queue = LinkedBlockingQueue<String>()
    }

    private val clients = CopyOnWriteArrayList<Client>()
    private val heartbeat = Executors.newSingleThreadScheduledExecutor { Thread(it, "sse-heartbeat").apply { isDaemon = true } }

    /** Starts the keep-alive ping. Call once, after the server is up. */
    fun startHeartbeat(periodSeconds: Long = 15) {
        heartbeat.scheduleAtFixedRate({ enqueue(": ping\n\n") }, periodSeconds, periodSeconds, TimeUnit.SECONDS)
    }

    /** Pushes a `data:` frame carrying [json] to every connected client. */
    fun broadcast(json: String) = enqueue(asEvent(json))

    /**
     * Handles one SSE request: emits [initialJson] immediately, then forwards every broadcast until
     * the client disconnects (a failed write) or the server shuts down (an interrupt).
     */
    fun stream(
        exchange: HttpExchange,
        initialJson: String,
    ) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Connection", "keep-alive")
        exchange.sendResponseHeaders(200, 0)
        val out = exchange.responseBody
        val client = Client()
        try {
            write(out, "retry: 3000\n\n" + asEvent(initialJson))
            clients.add(client)
            while (true) {
                write(out, client.queue.take())
            }
        } catch (_: Exception) {
            // Client went away or the server is shutting down — fall through to cleanup.
        } finally {
            clients.remove(client)
            runCatching { exchange.close() }
        }
    }

    override fun close() {
        heartbeat.shutdownNow()
    }

    private fun enqueue(frame: String) = clients.forEach { it.queue.offer(frame) }

    private fun asEvent(json: String) = "data: $json\n\n"

    private fun write(
        out: OutputStream,
        frame: String,
    ) {
        out.write(frame.toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }
}
