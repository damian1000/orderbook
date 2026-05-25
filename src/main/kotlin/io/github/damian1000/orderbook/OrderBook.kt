package io.github.damian1000.orderbook

interface OrderBook {
    fun addOrder(order: Order)
    fun removeOrder(orderId: Long)
    fun modifyOrder(orderId: Long, size: Long)
    fun getPrice(side: Char, level: Int): Double
    fun getTotalSize(side: Char, level: Int): Long
    fun getOrders(side: Char): List<Order>
}
