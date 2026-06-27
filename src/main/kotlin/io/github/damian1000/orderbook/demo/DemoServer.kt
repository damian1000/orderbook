package io.github.damian1000.orderbook.demo

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.damian1000.orderbook.MatchingEngine
import io.github.damian1000.orderbook.Order
import io.github.damian1000.orderbook.PlainOrderBook
import io.github.damian1000.orderbook.Price
import io.github.damian1000.orderbook.Side
import io.github.damian1000.orderbook.Trade
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * A tiny, dependency-free web demo of the order book + [MatchingEngine].
 *
 * Submit an order through the browser and watch it match resting liquidity and print to the tape,
 * or rest on the book. Everything is in-memory and single-user; the book is not thread-safe, so the
 * HTTP server runs on a single-threaded executor which serialises every request.
 *
 * Uses only the JDK's built-in [HttpServer] — no web framework — so it starts in milliseconds and
 * runs comfortably in a small heap.
 */
private val book = PlainOrderBook()
private val engine = MatchingEngine(book)
private val nextId = AtomicLong(1000)
private val tape = ArrayDeque<Trade>()
private const val TAPE_LIMIT = 25

private fun seed() {
    val resting =
        listOf(
            Triple("102.00", Side.OFFER, 12L),
            Triple("101.50", Side.OFFER, 8L),
            Triple("101.00", Side.OFFER, 5L),
            Triple("99.50", Side.BID, 6L),
            Triple("99.00", Side.BID, 10L),
            Triple("98.00", Side.BID, 15L),
        )
    resting.forEach { (price, side, size) -> book.addOrder(Order(nextId.getAndIncrement(), Price.of(price), side, size)) }
}

fun main() {
    seed()
    val port = (System.getenv("PORT") ?: "8080").toInt()
    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.executor = Executors.newSingleThreadExecutor()
    server.createContext("/", ::handle)
    server.start()
    println("Order book demo listening on :$port")
}

private fun handle(exchange: HttpExchange) {
    try {
        when (exchange.requestURI.path) {
            "/" -> respond(exchange, 200, "text/html; charset=utf-8", PAGE)
            "/api/state" -> respond(exchange, 200, "application/json", stateJson())
            "/api/order" -> respond(exchange, 200, "application/json", submit(exchange))
            else -> respond(exchange, 404, "text/plain", "not found")
        }
    } catch (e: IllegalArgumentException) {
        respond(exchange, 400, "application/json", """{"error":${quote(e.message ?: "bad request")}}""")
    }
}

private fun submit(exchange: HttpExchange): String {
    val params = queryParams(exchange.requestURI.rawQuery)
    val side = if (params["side"].equals("BID", true) || params["side"].equals("BUY", true)) Side.BID else Side.OFFER
    val price = Price.of(params["price"] ?: error("price required"))
    val size = (params["size"] ?: error("size required")).toLong()
    val trades = engine.submit(Order(nextId.getAndIncrement(), price, side, size))
    trades.forEach {
        tape.addFirst(it)
        if (tape.size > TAPE_LIMIT) tape.removeLast()
    }
    return """{"matched":${trades.size},${stateBody()}"""
}

private fun stateJson(): String = "{${stateBody()}"

private fun stateBody(): String {
    val bids = levels(Side.BID)
    val asks = levels(Side.OFFER)
    return """"bids":$bids,"asks":$asks,"tape":${tapeJson()}}"""
}

private fun levels(side: Side): String {
    val byPrice = LinkedHashMap<Price, Long>()
    book.getOrders(side).forEach { byPrice.merge(it.price, it.size, Long::plus) }
    return byPrice.entries.joinToString(prefix = "[", postfix = "]") { """{"price":${quote(it.key.toString())},"size":${it.value}}""" }
}

private fun tapeJson(): String =
    tape.joinToString(prefix = "[", postfix = "]") {
        """{"price":${quote(it.price.toString())},"size":${it.size},"side":${quote(it.incomingSide.name)}}"""
    }

private fun queryParams(raw: String?): Map<String, String> =
    (raw ?: "").split("&").filter { it.contains("=") }.associate {
        val (k, v) = it.split("=", limit = 2)
        k to java.net.URLDecoder.decode(v, StandardCharsets.UTF_8)
    }

private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

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

private val PAGE =
    """
    <!doctype html><html><head><meta charset="utf-8"><title>Kotlin Order Book — live demo</title>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
      body{font:14px/1.5 system-ui,sans-serif;margin:0;background:#0f172a;color:#e2e8f0}
      .wrap{max-width:760px;margin:0 auto;padding:24px}
      h1{font-size:20px;margin:0 0 4px} .sub{color:#94a3b8;margin:0 0 20px}
      form{display:flex;gap:8px;flex-wrap:wrap;align-items:end;background:#1e293b;padding:14px;border-radius:10px;margin-bottom:18px}
      label{display:flex;flex-direction:column;font-size:12px;color:#94a3b8;gap:4px}
      input,select{background:#0f172a;border:1px solid #334155;color:#e2e8f0;border-radius:6px;padding:7px 9px;font-size:14px}
      button{background:#2563eb;border:0;color:#fff;padding:9px 16px;border-radius:6px;font-size:14px;cursor:pointer}
      .cols{display:flex;gap:16px} .col{flex:1}
      table{width:100%;border-collapse:collapse;background:#1e293b;border-radius:8px;overflow:hidden}
      th,td{padding:6px 10px;text-align:right} th{color:#94a3b8;font-weight:600;font-size:12px;text-align:right}
      .asks td:first-child{color:#f87171}.bids td:first-child{color:#4ade80}
      h3{font-size:13px;color:#94a3b8;margin:18px 0 6px;text-transform:uppercase;letter-spacing:.05em}
      .tape td:nth-child(2){color:#cbd5e1}
      .buy{color:#4ade80}.sell{color:#f87171}
    </style></head><body><div class="wrap">
      <h1>Kotlin Order Book — live demo</h1>
      <p class="sub">Submit a limit order; watch it match resting liquidity (price-time priority) and print to the tape, or rest on the book.</p>
      <form onsubmit="return send(event)">
        <label>Side<select id="side"><option value="BID">Buy</option><option value="OFFER">Sell</option></select></label>
        <label>Price<input id="price" value="101.00" size="8"></label>
        <label>Size<input id="size" value="10" size="6"></label>
        <button>Submit order</button>
      </form>
      <div class="cols">
        <div class="col"><h3>Bids</h3><table class="bids"><thead><tr><th>Price</th><th>Size</th></tr></thead><tbody id="bids"></tbody></table></div>
        <div class="col"><h3>Asks</h3><table class="asks"><thead><tr><th>Price</th><th>Size</th></tr></thead><tbody id="asks"></tbody></table></div>
      </div>
      <h3>Trade tape</h3>
      <table class="tape"><thead><tr><th>Side</th><th>Price</th><th>Size</th></tr></thead><tbody id="tape"></tbody></table>
    <script>
      function px(p){return parseFloat(p).toFixed(2)}
      function rows(arr){return arr.map(l=>'<tr><td>'+px(l.price)+'</td><td>'+l.size+'</td></tr>').join('')||'<tr><td colspan=2>—</td></tr>'}
      function render(s){
        document.getElementById('bids').innerHTML=rows(s.bids);
        document.getElementById('asks').innerHTML=rows(s.asks);
        document.getElementById('tape').innerHTML=s.tape.map(t=>'<tr><td class="'+(t.side=='BID'?'buy':'sell')+'">'+(t.side=='BID'?'BUY':'SELL')+'</td><td>'+px(t.price)+'</td><td>'+t.size+'</td></tr>').join('')||'<tr><td colspan=3>no trades yet</td></tr>';
      }
      async function refresh(){render(await (await fetch('/api/state')).json())}
      async function send(e){e.preventDefault();
        const q='side='+side.value+'&price='+encodeURIComponent(price.value)+'&size='+size.value;
        render(await (await fetch('/api/order?'+q)).json());return false;}
      refresh();setInterval(refresh,2000);
    </script></div></body></html>
    """.trimIndent()
