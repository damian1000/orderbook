package io.github.damian1000.orderbook.model

data class Order(
    val id: Long,
    val price: Price,
    val side: Side,
    val size: Long,
) {
    init {
        require(size > 0) { "size must be positive, got $size" }
    }
}
