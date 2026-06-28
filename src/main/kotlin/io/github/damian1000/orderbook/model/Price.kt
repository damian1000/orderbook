package io.github.damian1000.orderbook.model

import java.math.BigDecimal

/**
 * A price held as an integer number of ticks at a fixed [SCALE] decimal places.
 *
 * Storing prices as scaled `Long`s keeps them exact — no binary floating-point
 * drift, so two logically equal prices always map to the same key — and makes
 * comparison a primitive `Long` operation. `BigDecimal` is touched only when
 * parsing or rendering a decimal string, never on the matching hot path.
 */
@JvmInline
value class Price(
    val ticks: Long,
) : Comparable<Price> {
    init {
        require(ticks >= 0) { "price must be non-negative, got $ticks ticks" }
    }

    override fun compareTo(other: Price): Int = ticks.compareTo(other.ticks)

    override fun toString(): String = BigDecimal.valueOf(ticks, SCALE).toPlainString()

    companion object {
        /** Decimal places retained in the integer tick representation. */
        const val SCALE: Int = 8

        /**
         * Parses a decimal string such as `"3.45"` into an exact [Price].
         *
         * @throws ArithmeticException if [text] carries more than [SCALE] decimal
         *         places (it would lose precision) or overflows a `Long`.
         * @throws NumberFormatException if [text] is not a valid decimal.
         */
        fun of(text: String): Price = Price(BigDecimal(text).movePointRight(SCALE).longValueExact())
    }
}
