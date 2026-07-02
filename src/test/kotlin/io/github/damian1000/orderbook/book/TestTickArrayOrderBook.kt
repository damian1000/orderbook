package io.github.damian1000.orderbook.book

import io.github.damian1000.orderbook.model.Price

class TestTickArrayOrderBook : OrderBookContractTest() {
    override fun newOrderBook(): OrderBook = TickArrayOrderBook(Price.of("0"), Price.of("1").ticks, 30)
}
