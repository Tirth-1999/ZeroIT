package org.fairshare.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import org.fairshare.app.model.AppState
import org.fairshare.app.model.Expense
import org.fairshare.app.model.Group
import org.fairshare.app.model.Member
import org.fairshare.app.model.Settlement
import org.fairshare.app.model.GroupVisibility
import org.fairshare.app.model.SELF_ID

class HomeBalanceToolsTest {
    private val rows = listOf(
        HomeBalanceItem("mixed", "Trip", mapOf("USD" to -500L, "EUR" to 900L)),
        HomeBalanceItem("owed", "Home", mapOf("USD" to 2000L)),
        HomeBalanceItem("owe", "Dinner", mapOf("USD" to -1000L)),
        HomeBalanceItem("settled", "Settled", mapOf("USD" to 0L)),
        HomeBalanceItem("empty", "Empty", emptyMap()),
        HomeBalanceItem("euro", "Euro", mapOf("EUR" to -900000L))
    )
    @Test fun newestGroupsUseCreationTimeAndLaterInsertionForTiesWithNonGroupLast() {
        val items = listOf(
            HomeBalanceItem("older", "Z older", emptyMap(), createdAt = 100L),
            HomeBalanceItem("first-tie", "A first", emptyMap(), createdAt = 200L),
            HomeBalanceItem("newest", "Z newest", emptyMap(), createdAt = 200L),
            HomeBalanceItem(NON_GROUP_ID, "Non-group expenses", emptyMap(), createdAt = 999L),
        )
        assertEquals(listOf("newest", "first-tie", "older", NON_GROUP_ID),
            homeBalanceItems(items, BalanceFilter.ALL, BalanceSort.NEWEST, "USD").map { it.id })
        assertEquals(listOf("first-tie", NON_GROUP_ID, "newest", "older"),
            homeBalanceItems(items, BalanceFilter.ALL, BalanceSort.NAME, "USD").map { it.id })
        val group = Group("g", "New", "EUR", listOf(SELF_ID), createdAt = 123L)
        assertEquals(123L, groupHomeBalanceItem(AppState(groups = listOf(group)), "g", "New").createdAt)
    }

    @Test fun filtersRetainMixedCurrencyDirectionsWithoutCancellingThem() {
        fun ids(filter: BalanceFilter) = homeBalanceItems(rows, filter, BalanceSort.NAME, "USD").map { it.id }.toSet()
        assertEquals(setOf("mixed", "owe", "euro"), ids(BalanceFilter.OWE))
        assertEquals(setOf("mixed", "owed"), ids(BalanceFilter.OWED))
        assertEquals(setOf("mixed", "owed", "owe", "euro"), ids(BalanceFilter.UNSETTLED))
        assertEquals(setOf("empty", "settled"), ids(BalanceFilter.SETTLED))
    }
    @Test fun sortingUsesOnlySelectedCurrencyAndKeepsUncomparableRowsLast() {
        assertEquals(listOf("owed", "owe", "mixed", "empty", "euro", "settled"), homeBalanceItems(rows, BalanceFilter.ALL, BalanceSort.LARGEST, "USD").map { it.id })
        assertEquals(listOf("mixed", "owe", "owed", "empty", "euro", "settled"), homeBalanceItems(rows, BalanceFilter.ALL, BalanceSort.SMALLEST, "USD").map { it.id })
        assertEquals(listOf("euro", "mixed", "owe", "empty", "owed", "settled"), homeBalanceItems(rows, BalanceFilter.ALL, BalanceSort.LARGEST, "EUR").map { it.id })
        assertEquals(listOf("owe", "mixed", "euro"), homeBalanceItems(rows, BalanceFilter.OWE, BalanceSort.LARGEST, "USD").map { it.id })
    }
    @Test fun magnitudeHandlesLongMinAndEqualAmountsUseStableNames() {
        val items = listOf(HomeBalanceItem("b", "Beta", mapOf("JPY" to Long.MAX_VALUE)), HomeBalanceItem("a", "Alpha", mapOf("JPY" to Long.MIN_VALUE)))
        assertEquals(listOf("a", "b"), homeBalanceItems(items, BalanceFilter.ALL, BalanceSort.LARGEST, "JPY").map { it.id })
        val ties = items.map { it.copy(balances = mapOf("JPY" to 100L)) }
        assertEquals(listOf("a", "b"), homeBalanceItems(ties, BalanceFilter.ALL, BalanceSort.LARGEST, "JPY").map { it.id })
    }
    @Test fun nonGroupZeroNetStillKeepsBothOriginalRepaymentsActionable() {
        val state = AppState(members = listOf(Member(SELF_ID, "You"), Member("alice", "Alice"), Member("bob", "Bob")), groups = listOf(
            Group("a", "Alice", "USD", listOf(SELF_ID, "alice"), kind = "DIRECT"),
            Group("b", "Bob", "USD", listOf(SELF_ID, "bob"), kind = "DIRECT"),
        ), expenses = listOf(
            Expense("a1", "a", "Alice owes you", 1000, SELF_ID, mapOf("alice" to 1000)),
            Expense("b1", "b", "You owe Bob", 1000, "bob", mapOf(SELF_ID to 1000)),
        ))
        val item = nonGroupHomeBalanceItem(state, "Non-group expenses")
        assertEquals(0L, item.balances["USD"])
        assertTrue(item.hasDebt)
        assertTrue(item.hasReceivable)
        listOf(BalanceFilter.OWE, BalanceFilter.OWED, BalanceFilter.UNSETTLED).forEach { filter ->
            assertEquals(listOf(item), homeBalanceItems(listOf(item), filter, BalanceSort.NAME, "USD"))
        }
        assertEquals(emptyList(), homeBalanceItems(listOf(item), BalanceFilter.SETTLED, BalanceSort.NAME, "USD"))
        val settled = state.copy(settlements = listOf(
            Settlement("s1", "a", "alice", SELF_ID, 1000),
            Settlement("s2", "b", SELF_ID, "bob", 1000),
        ))
        val settledItem = nonGroupHomeBalanceItem(settled, "Non-group expenses")
        assertFalse(settledItem.hasDebt)
        assertFalse(settledItem.hasReceivable)
        assertEquals(listOf(settledItem), homeBalanceItems(listOf(settledItem), BalanceFilter.SETTLED, BalanceSort.NAME, "USD"))
    }

    @Test fun friendStatusRetainsOpposingGroupDebtsAndHiddenContexts() {
        val state = AppState(members = listOf(Member(SELF_ID, "You"), Member("alice", "Alice")), groups = listOf(
            Group("a", "Trip", "EUR", listOf(SELF_ID, "alice")),
            Group("b", "Home", "EUR", listOf(SELF_ID, "alice"), visibility = GroupVisibility.LEFT),
        ), expenses = listOf(
            Expense("a1", "a", "Owed to you", 500, SELF_ID, mapOf("alice" to 500)),
            Expense("b1", "b", "You owe", 500, "alice", mapOf(SELF_ID to 500)),
        ))
        val item = friendHomeBalanceItem(state, "alice", "Alice")
        assertEquals(mapOf("EUR" to 0L), item.balances)
        assertTrue(item.hasDebt)
        assertTrue(item.hasReceivable)
        assertEquals(emptyList(), homeBalanceItems(listOf(item), BalanceFilter.SETTLED, BalanceSort.NAME, "EUR"))
    }

    @Test fun groupStatusUsesItsConfiguredRepaymentMode() {
        val group = Group("trip", "Trip", "USD", listOf(SELF_ID, "alice", "bob"), simplifyDebts = false)
        val state = AppState(groups = listOf(group), expenses = listOf(
            Expense("a1", "trip", "You owe Alice", 500, "alice", mapOf(SELF_ID to 500)),
            Expense("b1", "trip", "Bob owes you", 500, SELF_ID, mapOf("bob" to 500)),
        ))
        val direct = groupHomeBalanceItem(state, "trip", "Trip")
        assertEquals(0L, direct.balances["USD"])
        assertTrue(direct.hasDebt)
        assertTrue(direct.hasReceivable)
        val simplified = groupHomeBalanceItem(state.copy(groups = listOf(group.copy(simplifyDebts = true))), "trip", "Trip")
        assertFalse(simplified.hasDebt)
        assertFalse(simplified.hasReceivable)
    }

}
