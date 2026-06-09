package io.github.damian1000.orderbook

interface OrderBook {
    fun addOrder(order: Order)
    fun removeOrder(orderId: Long)
    fun modifyOrder(orderId: Long, size: Long)

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
