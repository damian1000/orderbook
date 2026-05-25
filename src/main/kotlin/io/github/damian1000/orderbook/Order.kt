package io.github.damian1000.orderbook

data class Order(val id: Long, val price: Double, val side: Char, val size: Long)
