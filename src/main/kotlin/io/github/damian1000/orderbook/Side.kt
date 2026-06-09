package io.github.damian1000.orderbook

/**
 * The two sides of an order book.
 *
 *  - [BID]   — buy order. Best bid is the highest price.
 *  - [OFFER] — sell order. Best offer is the lowest price.
 *
 * The single-char [code] (`B` / `O`) is kept for external serialization
 * compatibility — internal APIs work with the enum directly.
 */
enum class Side(val code: Char) {
    BID('B'),
    OFFER('O');

    companion object {
        fun fromCode(code: Char): Side = when (code) {
            'B' -> BID
            'O' -> OFFER
            else -> throw IllegalArgumentException(
                "Unknown side code '$code'. Expected 'B' (bid) or 'O' (offer).")
        }
    }
}
