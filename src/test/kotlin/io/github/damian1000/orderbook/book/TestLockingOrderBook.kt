package io.github.damian1000.orderbook.book

import io.github.damian1000.orderbook.model.Order
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TestLockingOrderBook : OrderBookContractTest() {
    override fun newOrderBook(): OrderBook = LockingOrderBook()

    @Test
    fun invalidOrderInputsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Order(100L, Price.of("1"), Side.BID, 0) }
        assertThrows(IllegalArgumentException::class.java) { Order(100L, Price.of("1"), Side.BID, -1) }
    }

    @Test
    fun sideFromCodeRejectsUnknown() {
        assertEquals(Side.BID, Side.fromCode('B'))
        assertEquals(Side.OFFER, Side.fromCode('O'))
        assertThrows(IllegalArgumentException::class.java) { Side.fromCode('X') }
    }
}
