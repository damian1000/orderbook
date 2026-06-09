package io.github.damian1000.orderbook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
        orderBook.addOrder(Order(1L, 19.0, Side.OFFER, 8))
        orderBook.addOrder(Order(2L, 19.0, Side.OFFER, 4))
        orderBook.addOrder(Order(5L, 22.0, Side.OFFER, 7))
        orderBook.addOrder(Order(3L, 21.0, Side.OFFER, 16))
        orderBook.addOrder(Order(4L, 21.0, Side.OFFER, 1))
        orderBook.addOrder(Order(6L, 15.0, Side.BID, 5))
        orderBook.modifyOrder(6L, 10)
        orderBook.addOrder(Order(7L, 13.0, Side.BID, 20))
        orderBook.removeOrder(7L)
        orderBook.addOrder(Order(8L, 10.0, Side.BID, 13))
        orderBook.addOrder(Order(9L, 10.0, Side.BID, 13))
    }

    @Test
    fun testGetPriceForOfferLevelOne() {
        assertEquals(19.0, orderBook.getPrice(Side.OFFER, 1)!!, DETA)
    }

    @Test
    fun testGetPriceForOfferLevelTwo() {
        assertEquals(21.0, orderBook.getPrice(Side.OFFER, 2)!!, DETA)
    }

    @Test
    fun testGetPriceForOfferLevelThree() {
        assertEquals(22.0, orderBook.getPrice(Side.OFFER, 3)!!, DETA)
    }

    @Test
    fun testGetPriceForOfferLevelFourIsNull() {
        assertNull(orderBook.getPrice(Side.OFFER, 4))
    }

    @Test
    fun testGetPriceForBidLevelOne() {
        assertEquals(15.0, orderBook.getPrice(Side.BID, 1)!!, DETA)
    }

    @Test
    fun testGetPriceForBidLevelTwo() {
        assertEquals(10.0, orderBook.getPrice(Side.BID, 2)!!, DETA)
    }

    @Test
    fun testGetPriceForBidLevelThreeIsNull() {
        assertNull(orderBook.getPrice(Side.BID, 3))
    }

    @Test
    fun testGetTotalSizeForOfferLevelOne() {
        assertEquals(12L, orderBook.getTotalSize(Side.OFFER, 1))
    }

    @Test
    fun testGetTotalSizeForOfferLevelTwo() {
        assertEquals(17L, orderBook.getTotalSize(Side.OFFER, 2))
    }

    @Test
    fun testGetTotalSizeForOfferLevelThree() {
        assertEquals(7L, orderBook.getTotalSize(Side.OFFER, 3))
    }

    @Test
    fun testGetTotalSizeForOfferLevelFour() {
        assertEquals(0L, orderBook.getTotalSize(Side.OFFER, 4))
    }

    @Test
    fun testGetTotalSizeForBidLevelOne() {
        assertEquals(10L, orderBook.getTotalSize(Side.BID, 1))
    }

    @Test
    fun testGetTotalSizeForBidLevelTwo() {
        assertEquals(26L, orderBook.getTotalSize(Side.BID, 2))
    }

    @Test
    fun testGetTotalSizeForBidLevelThree() {
        assertEquals(0L, orderBook.getTotalSize(Side.BID, 3))
    }

    @Test
    fun testGetOfferOrders() {
        val offers = orderBook.getOrders(Side.OFFER)
        assertEquals(5, offers.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), offers.map { it.id })
    }

    @Test
    fun testGetBidOrders() {
        val bids = orderBook.getOrders(Side.BID)
        assertEquals(3, bids.size)
        assertEquals(listOf(6L, 8L, 9L), bids.map { it.id })
    }

    @Test
    fun testModifyPreservesTimePriorityAtSamePrice() {
        orderBook.modifyOrder(1L, 12)

        val offers = orderBook.getOrders(Side.OFFER)
        assertEquals(1L, offers[0].id)
        assertEquals(12L, offers[0].size)
        assertEquals(2L, offers[1].id)
        assertEquals(16L, offers[2].size)
    }

    @Test
    fun testAddExistingIdRemovesOldOrder() {
        orderBook.addOrder(Order(1L, 23.0, Side.OFFER, 11))

        val offers = orderBook.getOrders(Side.OFFER)
        assertEquals(listOf(2L, 3L, 4L, 5L, 1L), offers.map { it.id })
        assertEquals(4L, orderBook.getTotalSize(Side.OFFER, 1))
        assertEquals(23.0, orderBook.getPrice(Side.OFFER, 4)!!, DETA)
        assertEquals(11L, orderBook.getTotalSize(Side.OFFER, 4))
    }

    @Test
    fun testAddExistingIdCanMoveSides() {
        orderBook.addOrder(Order(1L, 16.0, Side.BID, 11))

        assertEquals(listOf(2L, 3L, 4L, 5L), orderBook.getOrders(Side.OFFER).map { it.id })
        assertEquals(listOf(1L, 6L, 8L, 9L), orderBook.getOrders(Side.BID).map { it.id })
        assertEquals(16.0, orderBook.getPrice(Side.BID, 1)!!, DETA)
    }

    @Test
    fun modifyUnknownIdIsNoOp() {
        assertFalse(orderBook.modifyOrder(999L, 50))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), orderBook.getOrders(Side.OFFER).map { it.id })
        assertEquals(12L, orderBook.getTotalSize(Side.OFFER, 1))
    }

    @Test
    fun modifyKnownIdReturnsTrue() {
        assertTrue(orderBook.modifyOrder(1L, 99))
    }

    @Test
    fun removeUnknownIdIsNoOp() {
        assertFalse(orderBook.removeOrder(999L))
        assertEquals(5, orderBook.getOrders(Side.OFFER).size)
        assertEquals(3, orderBook.getOrders(Side.BID).size)
    }

    @Test
    fun removeKnownIdReturnsTrue() {
        assertTrue(orderBook.removeOrder(1L))
        assertFalse(orderBook.removeOrder(1L))
    }

    @Test
    fun nonPositiveLevelThrows() {
        assertThrows(IllegalArgumentException::class.java) { orderBook.getPrice(Side.OFFER, 0) }
        assertThrows(IllegalArgumentException::class.java) { orderBook.getPrice(Side.OFFER, -1) }
        assertThrows(IllegalArgumentException::class.java) { orderBook.getTotalSize(Side.OFFER, 0) }
        assertThrows(IllegalArgumentException::class.java) { orderBook.getTotalSize(Side.OFFER, -1) }
    }

    @Test
    fun emptyBookGetOrdersReturnsEmptyList() {
        val empty = KotlinOrderBook()
        assertTrue(empty.getOrders(Side.OFFER).isEmpty())
        assertTrue(empty.getOrders(Side.BID).isEmpty())
        assertNull(empty.getPrice(Side.OFFER, 1))
        assertEquals(0L, empty.getTotalSize(Side.BID, 1))
    }

    @Test
    fun invalidOrderInputsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Order(100L, 1.0, Side.BID, 0) }
        assertThrows(IllegalArgumentException::class.java) { Order(100L, 1.0, Side.BID, -1) }
        assertThrows(IllegalArgumentException::class.java) { Order(100L, -0.01, Side.BID, 1) }
        assertThrows(IllegalArgumentException::class.java) { Order(100L, Double.NaN, Side.BID, 1) }
        assertThrows(IllegalArgumentException::class.java) { Order(100L, Double.POSITIVE_INFINITY, Side.BID, 1) }
        assertThrows(IllegalArgumentException::class.java) { Order(100L, Double.NEGATIVE_INFINITY, Side.BID, 1) }
    }

    @Test
    fun modifyOrderRejectsNonPositiveSize() {
        assertThrows(IllegalArgumentException::class.java) { orderBook.modifyOrder(1L, 0) }
        assertThrows(IllegalArgumentException::class.java) { orderBook.modifyOrder(1L, -1) }
    }

    @Test
    fun sideFromCodeRejectsUnknown() {
        assertEquals(Side.BID, Side.fromCode('B'))
        assertEquals(Side.OFFER, Side.fromCode('O'))
        assertThrows(IllegalArgumentException::class.java) { Side.fromCode('X') }
    }
}
