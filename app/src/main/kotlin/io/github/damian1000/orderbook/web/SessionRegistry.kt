package io.github.damian1000.orderbook.web

import io.github.damian1000.orderbook.market.Market

/** Thrown for a symbol that fails basic shape validation before any book is created for it. */
class UnknownSymbolException(
    symbol: String,
) : IllegalArgumentException("unknown symbol '$symbol'")

/** A live [Market] plus the [Broadcaster] feeding its SSE stream — the unit [SessionRegistry] owns. */
class ManagedSession(
    val session: Market,
    val broadcaster: Broadcaster,
) : AutoCloseable {
    override fun close() {
        (session as? AutoCloseable)?.close()
        (broadcaster as? AutoCloseable)?.close()
    }
}

/**
 * Lazily creates a [ManagedSession] per symbol via [factory] on first request, and evicts the
 * least-recently-used one once [maxSessions] is exceeded — so free-text symbol entry can't grow
 * memory unboundedly. Backed by an access-ordered [LinkedHashMap]: [sessionFor] both reads and
 * (on a miss) writes, either of which counts as "used" and bumps the entry to the recent end.
 */
class SessionRegistry(
    private val maxSessions: Int = 20,
    private val factory: (symbol: String) -> ManagedSession,
) : AutoCloseable {
    private val sessions =
        object : LinkedHashMap<String, ManagedSession>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ManagedSession>): Boolean {
                val evict = size > maxSessions
                if (evict) eldest.value.close()
                return evict
            }
        }

    /** Number of currently open sessions — for tests asserting the eviction cap holds. */
    val size: Int @Synchronized get() = sessions.size

    @Synchronized
    fun sessionFor(rawSymbol: String): ManagedSession {
        val symbol = normalize(rawSymbol)
        return sessions.getOrPut(symbol) { factory(symbol) }
    }

    @Synchronized
    override fun close() = sessions.values.forEach { it.close() }

    companion object {
        // Uppercase alnum plus '.' (share classes, e.g. BRK.B), 1-10 characters: enough to reject
        // obvious junk before a book is ever created for it. Real-instrument validation (does
        // this actually resolve to a listed quote) is a later, separate concern.
        private val VALID_SYMBOL = Regex("^[A-Z][A-Z0-9.]{0,9}$")

        fun normalize(raw: String): String {
            val symbol = raw.trim().uppercase()
            if (!VALID_SYMBOL.matches(symbol)) throw UnknownSymbolException(raw)
            return symbol
        }
    }
}
