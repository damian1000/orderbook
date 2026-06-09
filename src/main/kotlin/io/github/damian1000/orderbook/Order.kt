package io.github.damian1000.orderbook

data class Order(val id: Long, val price: Double, val side: Side, val size: Long) {
    init {
        require(size > 0) { "size must be positive, got $size" }
        require(price.isFinite()) { "price must be finite, got $price" }
        require(price >= 0.0) { "price must be non-negative, got $price" }
    }
}
