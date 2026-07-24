package io.github.damian1000.orderbook.web

import io.github.damian1000.orderbook.market.MarketSession
import io.github.damian1000.orderbook.model.Side

/**
 * The order book's operational truth for `/readyz`. `/healthz` proves the web process answers; the
 * matching engine has no external dependency in its serve path, so the readiness signal that means
 * something is that the engine itself computes. [matchingEngine] builds a throwaway synthetic book
 * and fills a marketable order end to end, so a deploy whose matching path throws or mis-seeds reads
 * as not-ready (503) rather than serving a broken book on the first request.
 *
 * The check is injected so a test can force the failure path; the probe swallows any throw into a
 * 503, never dropping the connection.
 */
class Readiness(
    private val selfCheck: () -> Unit,
) {
    data class Probe(
        val ready: Boolean,
        val json: String,
    )

    fun probe(): Probe =
        try {
            selfCheck()
            Probe(true, """{"ready":true,"match":{"ok":true}}""")
        } catch (_: Exception) {
            Probe(false, """{"ready":false,"match":{"ok":false}}""")
        }

    companion object {
        /**
         * The default self-check: seed a throwaway [MarketSession] (the synthetic ladder, no live
         * quote or Kafka) and fill a marketable buy against it, closing the session's writer thread
         * afterwards. A broken matching path throws and the probe answers 503.
         */
        fun matchingEngine(): Readiness =
            Readiness {
                MarketSession().use { session ->
                    val book = session.snapshot()
                    check(book.bids.isNotEmpty() && book.asks.isNotEmpty()) { "seeded book has an empty side" }
                    check(session.submit(Side.BID, book.asks.first().price, 1).matched > 0) {
                        "matching engine did not fill a marketable order"
                    }
                }
            }
    }
}
