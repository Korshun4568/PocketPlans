package ru.slava.pocketplans

import org.junit.Assert.*
import org.junit.Test
import ru.slava.pocketplans.data.*
import ru.slava.pocketplans.ui.*

class RulesTest {
    private val phone = Product(1, "Phone", "Device", "tech", 550, 0)
    private val book = Product(2, "Book", "Novel", "books", 300, 0)
    @Test fun decimalMoneyIsExact() { assertEquals(1234L, priceCents("12.34")) }
    @Test fun commaAndWhitespaceAccepted() { assertEquals(120L, priceCents(" 1,20 ")) }
    @Test fun negativeMoneyRejected() { assertNull(priceCents("-1")) }
    @Test fun excessPrecisionRejected() { assertNull(priceCents("1.001")) }
    @Test fun hugeMoneyRejected() { assertNull(priceCents("1000000001")) }
    @Test fun invalidMoneyRejected() { assertNull(priceCents("abc")); assertNull(priceCents("")) }
    @Test fun querySearchesDescriptionIgnoringCase() {
        assertEquals(listOf(phone), selectProducts(listOf(phone, book), emptyList(), Filters("DEVICE"), false))
    }
    @Test fun categoryAndFavoritesCombine() {
        assertEquals(listOf(book), selectProducts(listOf(phone, book), listOf(Personal(2, favorite = true)), Filters(category = "books", favorites = true), false))
    }
    @Test fun sortingUsesPriceThenId() {
        assertEquals(listOf(book, phone), selectProducts(listOf(phone, book), emptyList(), Filters(), true))
    }
    @Test fun budgetUsesQuantity() { assertEquals(1400L, planTotal(listOf(PlanEntry(1, 1, 2), PlanEntry(1, 2)), listOf(phone, book))) }
    @Test fun purchasedItemsAreExcludedFromRemainingTotal() {
        assertEquals(300L, planTotal(listOf(PlanEntry(1, 1, 2, true), PlanEntry(1, 2)), listOf(phone, book), true))
    }
    @Test fun targetReachedIncludesEqualityButRequiresTarget() {
        assertTrue(targetReached(phone, Personal(1, targetCents = 550)))
        assertFalse(targetReached(phone, Personal(1, targetCents = 549)))
        assertFalse(targetReached(phone, Personal(1)))
    }
}
