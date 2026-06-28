package io.github.damian1000.orderbook.engine

import io.github.damian1000.orderbook.book.PlainOrderBook
import io.github.damian1000.orderbook.model.Order
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import io.github.damian1000.orderbook.model.Trade
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatchingEngineTest {
    private val book = PlainOrderBook()
    private val engine = MatchingEngine(book)

    private fun price(value: String): Price = Price.of(value)

    @Test
    fun restsWhenBookEmpty() {
        val trades = engine.submit(Order(1L, price("100"), Side.BID, 5L))

        assertTrue(trades.isEmpty())
        assertEquals(listOf(1L), book.getOrders(Side.BID).map { it.id })
        assertEquals(5L, book.getTotalSize(Side.BID, 1))
    }

    @Test
    fun restsWhenNoCross() {
        book.addOrder(Order(1L, price("101"), Side.OFFER, 5L))

        val trades = engine.submit(Order(2L, price("100"), Side.BID, 4L))

        assertTrue(trades.isEmpty())
        assertEquals(price("100"), book.getPrice(Side.BID, 1))
        assertEquals(5L, book.getTotalSize(Side.OFFER, 1)) // resting ask untouched
    }

    @Test
    fun fullyFillsSingleRestingOrderAndRemovesIt() {
        book.addOrder(Order(1L, price("101"), Side.OFFER, 5L))

        val trades = engine.submit(Order(2L, price("101"), Side.BID, 5L))

        assertEquals(listOf(Trade(price("101"), 5L, 1L, 2L, Side.BID)), trades)
        assertTrue(book.getOrders(Side.OFFER).isEmpty())
        assertTrue(book.getOrders(Side.BID).isEmpty()) // fully filled, nothing rests
    }

    @Test
    fun partiallyFillsRestingOrderLeavingRemainderOnBook() {
        book.addOrder(Order(1L, price("101"), Side.OFFER, 10L))

        val trades = engine.submit(Order(2L, price("101"), Side.BID, 4L))

        assertEquals(listOf(Trade(price("101"), 4L, 1L, 2L, Side.BID)), trades)
        assertEquals(6L, book.getTotalSize(Side.OFFER, 1)) // 10 - 4 left resting
        assertTrue(book.getOrders(Side.BID).isEmpty())
    }

    @Test
    fun incomingRemainderRestsWhenLiquidityExhausted() {
        book.addOrder(Order(1L, price("101"), Side.OFFER, 5L))

        val trades = engine.submit(Order(2L, price("101"), Side.BID, 8L))

        assertEquals(1, trades.size)
        assertTrue(book.getOrders(Side.OFFER).isEmpty())
        assertEquals(price("101"), book.getPrice(Side.BID, 1))
        assertEquals(3L, book.getTotalSize(Side.BID, 1)) // 8 - 5 rests at 101
    }

    @Test
    fun sweepsMultiplePriceLevelsBestFirst() {
        book.addOrder(Order(1L, price("101"), Side.OFFER, 5L))
        book.addOrder(Order(2L, price("102"), Side.OFFER, 8L))

        val trades = engine.submit(Order(3L, price("102"), Side.BID, 10L))

        assertEquals(
            listOf(
                Trade(price("101"), 5L, 1L, 3L, Side.BID),
                Trade(price("102"), 5L, 2L, 3L, Side.BID),
            ),
            trades,
        )
        assertEquals(3L, book.getTotalSize(Side.OFFER, 1)) // 8 - 5 left at 102
        assertTrue(book.getOrders(Side.BID).isEmpty())
    }

    @Test
    fun tradePrintsAtRestingPriceNotAggressorPrice() {
        book.addOrder(Order(1L, price("101"), Side.OFFER, 5L))

        val trades = engine.submit(Order(2L, price("105"), Side.BID, 5L)) // aggressive bid

        assertEquals(price("101"), trades.single().price) // price improvement accrues to the taker
    }

    @Test
    fun fillsOldestFirstAtSamePrice() {
        book.addOrder(Order(1L, price("101"), Side.OFFER, 5L)) // older
        book.addOrder(Order(2L, price("101"), Side.OFFER, 5L)) // newer

        val trades = engine.submit(Order(3L, price("101"), Side.BID, 5L))

        assertEquals(1L, trades.single().restingOrderId) // oldest at the level filled first
        assertEquals(listOf(2L), book.getOrders(Side.OFFER).map { it.id })
    }

    @Test
    fun offerAggressorMatchesBestBidsFirst() {
        book.addOrder(Order(1L, price("100"), Side.BID, 6L))
        book.addOrder(Order(2L, price("99"), Side.BID, 10L))

        val trades = engine.submit(Order(3L, price("99"), Side.OFFER, 8L))

        assertEquals(
            listOf(
                Trade(price("100"), 6L, 1L, 3L, Side.OFFER),
                Trade(price("99"), 2L, 2L, 3L, Side.OFFER),
            ),
            trades,
        )
        assertEquals(8L, book.getTotalSize(Side.BID, 1)) // 10 - 2 left at 99
        assertTrue(book.getOrders(Side.OFFER).isEmpty())
    }
}
