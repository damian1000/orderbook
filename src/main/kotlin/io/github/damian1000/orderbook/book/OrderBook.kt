package io.github.damian1000.orderbook.book

import io.github.damian1000.orderbook.model.Order
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side

interface OrderBook {
    /** Adds the order; a duplicate `id` is replaced (last-write-wins). */
    fun addOrder(order: Order)

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

    /** Resting orders on `side`, best price first then time order. */
    fun getOrders(side: Side): List<Order>
}
