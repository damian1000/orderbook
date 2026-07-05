package io.github.damian1000.orderbook.web

import io.github.damian1000.orderbook.market.MarketSession
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Loopback tests over a real [WebServer] on an ephemeral port — the transport is driven end to
 * end (routing, method enforcement, error mapping, SSE), not mocked.
 */
class WebServerTest {
    private lateinit var session: MarketSession
    private lateinit var broadcaster: SseBroadcaster
    private lateinit var server: WebServer
    private val client: HttpClient = HttpClient.newHttpClient()

    @BeforeEach
    fun start() {
        session = MarketSession()
        broadcaster = SseBroadcaster()
        server = WebServer(session, WebAssets.load(), broadcaster, port = 0)
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop()
        broadcaster.close()
        session.close()
    }

    private fun request(
        method: String,
        path: String,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:${server.boundPort}$path"))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `healthz responds ok`() {
        val response = request("GET", "/healthz")
        assertEquals(200, response.statusCode())
        assertEquals("ok", response.body())
    }

    @Test
    fun `serves the static front end with content types`() {
        assertTrue(request("GET", "/").body().contains("<html"))
        assertEquals("text/css; charset=utf-8", request("GET", "/app.css").headers().firstValue("Content-Type").get())
        assertEquals("text/javascript; charset=utf-8", request("GET", "/app.js").headers().firstValue("Content-Type").get())
    }

    @Test
    fun `state returns the book as JSON`() {
        val response = request("GET", "/api/state")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("\"bids\":["))
        assertTrue(response.body().contains("\"asks\":["))
    }

    @Test
    fun `unknown path is a 404`() {
        assertEquals(404, request("GET", "/nope").statusCode())
    }

    @Test
    fun `a valid order submits and reports fills`() {
        val response = request("POST", "/api/order?side=BUY&price=102.00&size=3")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().startsWith("""{"matched":"""))
    }

    @Test
    fun `order submission rejects GET - state changes are POST-only`() {
        val response = request("GET", "/api/order?side=BUY&price=100&size=1")
        assertEquals(405, response.statusCode())
        assertEquals("POST", response.headers().firstValue("Allow").get())
    }

    @Test
    fun `read endpoints reject non-GET methods`() {
        assertEquals(405, request("POST", "/api/state").statusCode())
        assertEquals(405, request("DELETE", "/healthz").statusCode())
    }

    @Test
    fun `an unknown side is a 400, not a silent sell`() {
        val response = request("POST", "/api/order?side=BANANA&price=100&size=1")
        assertEquals(400, response.statusCode())
        assertTrue(response.body().contains("\"error\""))
    }

    @Test
    fun `a missing parameter is a 400`() {
        assertEquals(400, request("POST", "/api/order?side=BUY&size=1").statusCode())
        assertEquals(400, request("POST", "/api/order?side=BUY&price=100").statusCode())
    }

    @Test
    fun `a non-positive size is a 400`() {
        val response = request("POST", "/api/order?side=BUY&price=100&size=0")
        assertEquals(400, response.statusCode())
        assertTrue(response.body().contains("\"error\""))
    }

    @Test
    fun `a malformed size is a 400`() {
        assertEquals(400, request("POST", "/api/order?side=BUY&price=100&size=lots").statusCode())
    }

    @Test
    fun `an over-precise price is a 400`() {
        val response = request("POST", "/api/order?side=BUY&price=1.123456789&size=1")
        assertEquals(400, response.statusCode())
        assertTrue(response.body().contains("\"error\""))
    }

    @Test
    fun `a malformed price is a 400`() {
        assertEquals(400, request("POST", "/api/order?side=BUY&price=abc&size=1").statusCode())
    }

    @Test
    fun `the SSE stream sends the initial snapshot and pushes submitted orders`() {
        val connection = URI("http://localhost:${server.boundPort}/api/stream").toURL().openConnection() as HttpURLConnection
        connection.readTimeout = 5_000
        connection.inputStream.bufferedReader().use { reader ->
            assertTrue(dataFrame(reader).contains("\"bids\":["), "initial snapshot expected on connect")

            request("POST", "/api/order?side=BUY&price=102.00&size=1")
            assertTrue(dataFrame(reader).contains("\"tape\":["), "broadcast snapshot expected after a submit")
        }
        connection.disconnect()
    }

    private fun dataFrame(reader: BufferedReader): String {
        while (true) {
            val line = reader.readLine() ?: error("stream closed before a data frame arrived")
            if (line.startsWith("data: ")) return line.removePrefix("data: ")
        }
    }
}
