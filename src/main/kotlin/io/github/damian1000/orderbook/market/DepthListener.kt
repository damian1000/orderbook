package io.github.damian1000.orderbook.market

import io.github.damian1000.orderbook.view.MarketSnapshot

/**
 * Receives the book's post-mutation snapshot: once for the freshly seeded book, then after every
 * accepted submit. Each snapshot supersedes the last, so a consumer needs only the newest one —
 * which is what lets the egress shed stale depth under pressure without corrupting anyone
 * downstream. Same contract as [FillListener]: invoked on the writer thread, return quickly,
 * never block.
 */
fun interface DepthListener {
    fun onDepth(snapshot: MarketSnapshot)

    companion object {
        /** Discards snapshots — the default for a market with no egress attached. */
        val NONE = DepthListener { }
    }
}
