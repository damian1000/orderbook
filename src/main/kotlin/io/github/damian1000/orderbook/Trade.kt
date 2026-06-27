package io.github.damian1000.orderbook

/**
 * A fill produced when an incoming order crosses resting liquidity.
 *
 * Trades print at the **resting** (passive / maker) order's price — the incoming
 * (aggressor / taker) order pays the price already on the book, which is where any
 * price improvement accrues to the taker.
 *
 * @property price          the resting order's price, at which this fill executed
 * @property size           quantity filled
 * @property restingOrderId the passive order that was sitting on the book (maker)
 * @property incomingOrderId the aggressor order that crossed the spread (taker)
 * @property incomingSide    the aggressor's side
 */
data class Trade(
    val price: Price,
    val size: Long,
    val restingOrderId: Long,
    val incomingOrderId: Long,
    val incomingSide: Side,
)
