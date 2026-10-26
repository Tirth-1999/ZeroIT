package org.fairshare.app.ui

import org.fairshare.app.model.*
import org.fairshare.app.platform.parseCalendarDate
import kotlin.test.*

class FriendHomeBatchTest {
    private fun assertParity(state: AppState) {
        val expected = state.members.filter { it.id != SELF_ID }.map { friendHomeBalanceItem(state, it.id, it.name) }
        assertEquals(expected, friendHomeBalanceItems(state))
    }

    @Test fun sixtyPeopleTwoYearsThreeCurrenciesMatchOriginalFriendOracle() {
        val people = listOf(Member(SELF_ID, "You")) + (1..59).map { Member("p$it", "Person $it") }
        val ids = people.map { it.id }
        val start = parseCalendarDate("2024-11-01")
        val state = AppState(members = people, groups = listOf(Group("g", "Two years", memberIds = ids)),
            expenses = (0 until 730).map { index -> Expense("e$index", "g", "Day $index", 6000,
                ids[index % 60], ids.associateWith { 100L }, date = start + index * 86_400_000L, currency = listOf("USD", "JPY", "KWD")[index % 3]) })
        assertParity(state)
    }

    @Test fun zeroNetFlagsHiddenRemovedEmptyAndBilateralContextsPreserveParity() {
        val people = listOf(Member(SELF_ID, "You"), Member("a", "Alice"), Member("b", "Bob"), Member("empty", "No expenses"))
        val groups = listOf(
            Group("g1", "Active", "EUR", listOf(SELF_ID, "a", "b"), simplifyDebts = false),
            Group("g2", "Hidden", "EUR", listOf(SELF_ID, "a"), visibility = GroupVisibility.LEFT),
            Group("g3", "Removed", "JPY", listOf(SELF_ID, "b"), removedMemberIds = listOf("b")))
        val state = AppState(members = people, groups = groups, expenses = listOf(
            Expense("a", "g1", "Credit", 500, SELF_ID, mapOf("a" to 500)),
            Expense("b", "g2", "Debt", 500, "a", mapOf(SELF_ID to 500)),
            Expense("c", "g1", "Bilateral", 200, "b", mapOf(SELF_ID to 200)),
            Expense("deleted", "g3", "Deleted", 100, "b", mapOf(SELF_ID to 100), deleted = true)))
        assertParity(state)
        val rows = friendHomeBalanceItems(state).associateBy { it.id }
        assertEquals(mapOf("EUR" to 0L), rows.getValue("a").balances)
        assertTrue(rows.getValue("a").hasDebt)
        assertTrue(rows.getValue("a").hasReceivable)
        assertEquals(0L, rows.getValue("b").balances["JPY"])
        assertEquals(emptyMap(), rows.getValue("empty").balances)
        assertParity(state.copy(groups = groups.map { it.copy(simplifyDebts = true) }))
    }

    @Test fun hugeIntermediateCancellationIsExactAndFinalOverflowFails() {
        val people = listOf(Member(SELF_ID, "You"), Member("a", "Alice"))
        val groups = listOf("first", "second", "reverse").map { Group(it, it, memberIds = people.map { member -> member.id }) }
        val expenses = listOf(
            Expense("e1", "first", "First", Long.MAX_VALUE, SELF_ID, mapOf("a" to Long.MAX_VALUE)),
            Expense("e2", "second", "Second", Long.MAX_VALUE, SELF_ID, mapOf("a" to Long.MAX_VALUE)),
            Expense("e3", "reverse", "Reverse", Long.MAX_VALUE, "a", mapOf(SELF_ID to Long.MAX_VALUE)))
        val state = AppState(members = people, groups = groups, expenses = expenses)
        assertParity(state)
        assertEquals(Long.MAX_VALUE, friendHomeBalanceItems(state).single().balances["USD"])
        val overflow = state.copy(expenses = expenses.take(2))
        assertFailsWith<IllegalArgumentException> { friendHomeBalanceItems(overflow) }
        assertFailsWith<IllegalArgumentException> { friendHomeBalanceItem(overflow, "a", "Alice") }
    }
}
