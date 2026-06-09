package io.github.damian1000.orderbook

interface OrderBook {
    /**
     * Adds `order` to the book. If an order with the same `id` already exists,
     * it is removed first and `order` takes its place (last-write-wins).
     */
    fun addOrder(order: Order)

    /** @return `true` if an order with `orderId` existed and was removed. */
    fun removeOrder(orderId: Long): Boolean

    /** @return `true` if an order with `orderId` existed and was modified. */
    fun modifyOrder(orderId: Long, size: Long): Boolean

    /**
     * @return the price at `level` on `side`, or `null` when fewer than
     *         `level` price levels exist on that side. `level=1` is the best
     *         price on that side.
     * @throws IllegalArgumentException if `level <= 0`.
     */
    fun getPrice(side: Side, level: Int): Double?

    /**
     * @return the sum of order sizes at `level` on `side`, or `0` when fewer
     *         than `level` price levels exist on that side.
     * @throws IllegalArgumentException if `level <= 0`.
     */
    fun getTotalSize(side: Side, level: Int): Long

    fun getOrders(side: Side): List<Order>
}
