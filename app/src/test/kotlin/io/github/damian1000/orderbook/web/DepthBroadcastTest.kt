package io.github.damian1000.orderbook.web

import com.sun.net.httpserver.HttpExchange
import io.github.damian1000.orderbook.model.Order
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import io.github.damian1000.orderbook.view.MarketSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DepthBroadcastTest {
    @Test
    fun `serialises each snapshot and hands it to the broadcaster`() {
        val frames = mutableListOf<String>()
        val broadcaster =
            object : Broadcaster {
                override fun startHeartbeat(periodSeconds: Long) {}

                override fun broadcast(json: String) {
                    frames += json
                }

                override fun stream(
                    exchange: HttpExchange,
                    initialJson: String,
                ) {}
            }
        val snapshot =
            MarketSnapshot.of(
                bids = listOf(Order(1, Price.of("99"), Side.BID, 5)),
                asks = emptyList(),
                tape = emptyList(),
                timeMillis = 7,
            )

        DepthBroadcast(broadcaster).onDepth(snapshot)

        assertEquals(listOf(snapshot.toJson()), frames)
    }
}
