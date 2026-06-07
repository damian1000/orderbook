package io.github.damian1000.orderbook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TestKotlinOrderBook {
    private lateinit var orderBook: OrderBook

    companion object {
        const val DETA = 0.00000001
    }

    @BeforeEach
    fun setup() {
        orderBook = KotlinOrderBook()
        orderBook.addOrder(Order(1L, 19.0, 'O', 8))
        orderBook.addOrder(Order(2L, 19.0, 'O', 4))
        orderBook.addOrder(Order(5L, 22.0, 'O', 7))
        orderBook.addOrder(Order(3L, 21.0, 'O', 16))
        orderBook.addOrder(Order(4L, 21.0, 'O', 1))
        orderBook.addOrder(Order(6L, 15.0, 'B', 5))
        orderBook.modifyOrder(6L, 10)
        orderBook.addOrder(Order(7L, 13.0, 'B', 20))
        orderBook.removeOrder(7L)
        orderBook.addOrder(Order(8L, 10.0, 'B', 13))
        orderBook.addOrder(Order(9L, 10.0, 'B', 13))
    }

    @Test
    fun testGetPriceForOfferLevelOne() {
        assertEquals(19.0, orderBook.getPrice('O', 1), DETA)
    }

    @Test
    fun testGetPriceForOfferLevelTwo() {
        assertEquals(21.0, orderBook.getPrice('O', 2), DETA)
    }

    @Test
    fun testGetPriceForOfferLevelThree() {
        assertEquals(22.0, orderBook.getPrice('O', 3), DETA)
    }

    @Test
    fun testGetPriceForOfferLevelFour() {
        assertEquals(0.0, orderBook.getPrice('O', 4), DETA)
    }

    @Test
    fun testGetPriceForBidLevelOne() {
        assertEquals(15.0, orderBook.getPrice('B', 1), DETA)
    }

    @Test
    fun testGetPriceForBidLevelTwo() {
        assertEquals(10.0, orderBook.getPrice('B', 2), DETA)
    }

    @Test
    fun testGetPriceForBidLevelThree() {
        assertEquals(0.0, orderBook.getPrice('B', 3), DETA)
    }

    @Test
    fun testGetTotalSizeForOfferLevelOne() {
        assertEquals(12.0, orderBook.getTotalSize('O', 1).toDouble(), DETA)
    }

    @Test
    fun testGetTotalSizeForOfferLevelTwo() {
        assertEquals(17.0, orderBook.getTotalSize('O', 2).toDouble(), DETA)
    }

    @Test
    fun testGetTotalSizeForOfferLevelThree() {
        assertEquals(7.0, orderBook.getTotalSize('O', 3).toDouble(), DETA)
    }

    @Test
    fun testGetTotalSizeForOfferLevelFour() {
        assertEquals(0.0, orderBook.getTotalSize('O', 4).toDouble(), DETA)
    }

    @Test
    fun testGetTotalSizeForBidLevelOne() {
        assertEquals(10.0, orderBook.getTotalSize('B', 1).toDouble(), DETA)
    }

    @Test
    fun testGetTotalSizeForBidLevelTwo() {
        assertEquals(26.0, orderBook.getTotalSize('B', 2).toDouble(), DETA)
    }

    @Test
    fun testGetTotalSizeForBidLevelThree() {
        assertEquals(0.0, orderBook.getTotalSize('B', 3).toDouble(), DETA)
    }

    @Test
    fun testGetOfferOrders() {
        val offers = orderBook.getOrders('O')
        assertEquals(5.0, offers.size.toDouble(), DETA)
        assertEquals(1.0, offers[0].id.toDouble(), DETA)
        assertEquals(2.0, offers[1].id.toDouble(), DETA)
        assertEquals(3.0, offers[2].id.toDouble(), DETA)
        assertEquals(4.0, offers[3].id.toDouble(), DETA)
        assertEquals(5.0, offers[4].id.toDouble(), DETA)
    }

    @Test
    fun testGetBidOrders() {
        val bids = orderBook.getOrders('B')
        assertEquals(3.0, bids.size.toDouble(), DETA)
        assertEquals(6.0, bids[0].id.toDouble(), DETA)
        assertEquals(8.0, bids[1].id.toDouble(), DETA)
        assertEquals(9.0, bids[2].id.toDouble(), DETA)
    }

    @Test
    fun testModifyPreservesTimePriorityAtSamePrice() {
        orderBook.modifyOrder(1L, 12)

        val offers = orderBook.getOrders('O')
        assertEquals(1L, offers[0].id)
        assertEquals(12L, offers[0].size)
        assertEquals(2L, offers[1].id)
        assertEquals(16L, offers[2].size)
    }

    @Test
    fun testAddExistingIdRemovesOldOrder() {
        orderBook.addOrder(Order(1L, 23.0, 'O', 11))

        val offers = orderBook.getOrders('O')
        assertEquals(listOf(2L, 3L, 4L, 5L, 1L), offers.map { it.id })
        assertEquals(4L, orderBook.getTotalSize('O', 1))
        assertEquals(23.0, orderBook.getPrice('O', 4), DETA)
        assertEquals(11L, orderBook.getTotalSize('O', 4))
    }

    @Test
    fun testAddExistingIdCanMoveSides() {
        orderBook.addOrder(Order(1L, 16.0, 'B', 11))

        assertEquals(listOf(2L, 3L, 4L, 5L), orderBook.getOrders('O').map { it.id })
        assertEquals(listOf(1L, 6L, 8L, 9L), orderBook.getOrders('B').map { it.id })
        assertEquals(16.0, orderBook.getPrice('B', 1), DETA)
    }

    @Test
    fun modifyUnknownIdIsNoOp() {
        orderBook.modifyOrder(999L, 50)
        // No change to existing book state
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), orderBook.getOrders('O').map { it.id })
        assertEquals(12L, orderBook.getTotalSize('O', 1))
    }

    @Test
    fun removeUnknownIdIsNoOp() {
        orderBook.removeOrder(999L)
        assertEquals(5, orderBook.getOrders('O').size)
        assertEquals(3, orderBook.getOrders('B').size)
    }

    @Test
    fun invalidSideThrowsOnEveryQuery() {
        assertThrows(IllegalArgumentException::class.java) { orderBook.getPrice('X', 1) }
        assertThrows(IllegalArgumentException::class.java) { orderBook.getTotalSize('X', 1) }
        assertThrows(IllegalArgumentException::class.java) { orderBook.getOrders('X') }
        assertThrows(IllegalArgumentException::class.java) { orderBook.addOrder(Order(99L, 1.0, 'X', 1)) }
    }

    @Test
    fun levelZeroAndNegativeReturnZero() {
        assertEquals(0.0, orderBook.getPrice('O', 0), DETA)
        assertEquals(0.0, orderBook.getPrice('O', -1), DETA)
        assertEquals(0L, orderBook.getTotalSize('O', 0))
        assertEquals(0L, orderBook.getTotalSize('O', -1))
    }

    @Test
    fun emptyBookGetOrdersReturnsEmptyList() {
        val empty = KotlinOrderBook()
        assertTrue(empty.getOrders('O').isEmpty())
        assertTrue(empty.getOrders('B').isEmpty())
        assertEquals(0.0, empty.getPrice('O', 1), DETA)
        assertEquals(0L, empty.getTotalSize('B', 1))
    }
}
