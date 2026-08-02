package io.github.damian1000.orderbook.book

import io.github.damian1000.orderbook.model.Order
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side

interface OrderBook {
    /**
     * Adds the order. This is the storage primitive and it does not police identity: a duplicate
     * `id` replaces the resting order, which silently cancels live liquidity. Callers admitting
     * client-supplied ids must reject a duplicate first — [contains] is the check, and
     * [io.github.damian1000.orderbook.engine.MatchingEngine] does it for everything it submits.
     */
    fun addOrder(order: Order)

    /** True while an order with this id is resting. O(1) — the guard a caller needs before [addOrder]. */
    fun contains(orderId: Long): Boolean

    fun removeOrder(orderId: Long): Boolean

    fun modifyOrder(
        orderId: Long,
        size: Long,
    ): Boolean

    /** Price at `level` (1 = best) on `side`, or null if that level doesn't exist. `level <= 0` throws. */
    fun getPrice(
        side: Side,
        level: Int,
    ): Price?

    /** Summed size at `level` (1 = best) on `side`, or 0 if that level doesn't exist. `level <= 0` throws. */
    fun getTotalSize(
        side: Side,
        level: Int,
    ): Long

    /** Resting orders on `side`, best price first then time order. Each is a detached snapshot. */
    fun getOrders(side: Side): List<Order>

    /**
     * The next order to fill on `side` — best price, oldest at that price — or null if the side is
     * empty. A detached snapshot. O(log P): lets the matcher peek the top of book without
     * materialising the whole side (which `getOrders` would).
     */
    fun bestResting(side: Side): Order?
}
