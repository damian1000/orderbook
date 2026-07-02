package io.github.damian1000.orderbook.book

import io.github.damian1000.orderbook.model.Order
import io.github.damian1000.orderbook.model.Price
import io.github.damian1000.orderbook.model.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Behaviour specific to the bounded, array-indexed design that [OrderBookContractTest] — shared
 * with the unbounded [PlainOrderBook]-backed implementations — can't exercise.
 */
class TickArrayOrderBookTest {
    private val unit = Price.of("1").ticks

    @Test
    fun constructorRejectsNonPositiveTickSize() {
        assertThrows(IllegalArgumentException::class.java) { TickArrayOrderBook(Price.of("0"), 0, 10) }
        assertThrows(IllegalArgumentException::class.java) { TickArrayOrderBook(Price.of("0"), -1, 10) }
    }

    @Test
    fun constructorRejectsNonPositiveLevels() {
        assertThrows(IllegalArgumentException::class.java) { TickArrayOrderBook(Price.of("0"), unit, 0) }
        assertThrows(IllegalArgumentException::class.java) { TickArrayOrderBook(Price.of("0"), unit, -1) }
    }

    @Test
    fun addOrderRejectsPriceBelowReference() {
        val book = TickArrayOrderBook(Price.of("100"), unit, 10)
        assertThrows(IllegalArgumentException::class.java) {
            book.addOrder(Order(1L, Price.of("99"), Side.BID, 5))
        }
    }

    @Test
    fun addOrderRejectsPriceAtOrAboveBandTop() {
        val book = TickArrayOrderBook(Price.of("0"), unit, 10)
        assertThrows(IllegalArgumentException::class.java) {
            book.addOrder(Order(1L, Price.of("10"), Side.BID, 5))
        }
    }

    @Test
    fun addOrderRejectsPriceNotOnTickGrid() {
        val book = TickArrayOrderBook(Price.of("0"), unit, 10)
        assertThrows(IllegalArgumentException::class.java) {
            book.addOrder(Order(1L, Price.of("1.5"), Side.BID, 5))
        }
    }

    @Test
    fun emptyingBestBidScansPastGapsToNextPopulatedLevel() {
        val book = TickArrayOrderBook(Price.of("0"), unit, 20)
        book.addOrder(Order(1L, Price.of("5"), Side.BID, 5))
        book.addOrder(Order(2L, Price.of("15"), Side.BID, 5))

        assertEquals(Price.of("15"), book.bestResting(Side.BID)?.price)

        book.removeOrder(2L)

        assertEquals(Price.of("5"), book.bestResting(Side.BID)?.price)
    }

    @Test
    fun emptyingBestOfferScansPastGapsToNextPopulatedLevel() {
        val book = TickArrayOrderBook(Price.of("0"), unit, 20)
        book.addOrder(Order(1L, Price.of("15"), Side.OFFER, 5))
        book.addOrder(Order(2L, Price.of("5"), Side.OFFER, 5))

        assertEquals(Price.of("5"), book.bestResting(Side.OFFER)?.price)

        book.removeOrder(2L)

        assertEquals(Price.of("15"), book.bestResting(Side.OFFER)?.price)
    }

    @Test
    fun emptyingOnlyLevelResetsSideToEmpty() {
        val book = TickArrayOrderBook(Price.of("0"), unit, 20)
        book.addOrder(Order(1L, Price.of("5"), Side.BID, 5))

        book.removeOrder(1L)

        assertNull(book.bestResting(Side.BID))
        assertNull(book.getPrice(Side.BID, 1))
        assertEquals(0L, book.getTotalSize(Side.BID, 1))
    }
}
