package org.fairshare.app.ui

import org.fairshare.app.domain.Ledger
import org.fairshare.app.math.BigInteger
import org.fairshare.app.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeLedgerPresentationTest {
    private fun mixedState() = AppState(
        members = listOf(Member(SELF_ID, "You"), Member("a", "Alice"), Member("b", "Bob")),
        groups = listOf(
            Group("active", "Trip", "USD", listOf(SELF_ID, "a")),
            Group("hidden", "Archived", "GBP", listOf(SELF_ID, "a"), visibility = GroupVisibility.LEFT),
            Group("direct", "Bob", "EUR", listOf(SELF_ID, "b"), kind = "DIRECT"),
        ),
        expenses = listOf(
            Expense("usd", "active", "Dinner", 1200, SELF_ID, mapOf("a" to 1200), currency = "USD"),
            Expense("eur", "active", "Train", 600, "a", mapOf(SELF_ID to 600), currency = "EUR"),
            Expense("gbp", "hidden", "Hotel", 300, SELF_ID, mapOf("a" to 300)),
            Expense("direct-eur", "direct", "Coffee", 700, SELF_ID, mapOf("b" to 700)),
            Expense("deleted", "active", "Removed", 900, SELF_ID, mapOf("a" to 900), currency = "JPY", deleted = true),
        ),
        settlements = listOf(
            Settlement("usd-payment", "active", "a", SELF_ID, 200, currency = "USD"),
            Settlement("eur-payment", "direct", "b", SELF_ID, 100, currency = "EUR"),
            Settlement("deleted-payment", "active", "a", SELF_ID, 500, currency = "USD", deleted = true),
        ),
        activity = listOf(
            Activity("regular-event", "active", "Dinner added", at = 1, expenseId = "usd"),
            Activity("direct-event", "direct", "Coffee added", at = 2, expenseId = "direct-eur"),
        ),
    )

    @Test fun cachedBalancesMatchLedgerAcrossCurrenciesHiddenDirectAndDeletedRecords() {
        val state = mixedState()
        val presentation = homeLedgerPresentation(state)
        val currencies = state.groups.flatMap { Ledger.groupCurrencies(state, it.id) }.distinct()
        val expectedOverall = currencies.associateWith { currency ->
            state.groups.fold(BigInteger.ZERO) { total, group ->
                total + BigInteger.valueOf(Ledger.balances(state, group.id, currency)[SELF_ID] ?: 0L)
            }
        }.filterValues { it.signum() != 0 }
        assertEquals(expectedOverall, presentation.overallBalances)
        // Opposing EUR obligations cancel overall; archived GBP remains financial data.
        assertEquals(mapOf("USD" to BigInteger.valueOf(1000), "GBP" to BigInteger.valueOf(300)), presentation.overallBalances)
        state.groups.forEach { group ->
            assertEquals(groupHomeBalanceItem(state, group.id, group.name), presentation.groupItemsById[group.id])
        }
        state.members.filter { it.id != SELF_ID }.forEach { friend ->
            assertEquals(Ledger.friendBalances(state, friend.id), presentation.friendItems.single { it.id == friend.id }.balances)
        }
        assertEquals(listOf("EUR", "USD"), presentation.groupCurrencies)
        assertEquals(listOf("EUR", "GBP", "USD"), presentation.friendCurrencies)
        assertFalse("JPY" in presentation.groupCurrencies)
        assertEquals(nonGroupHomeBalanceItem(state, "Non-group expenses"), presentation.nonGroupItem)
        assertEquals(mapOf("EUR" to 600L), presentation.nonGroupItem.balances)
    }

    @Test fun rebuildingAfterActivityOnlyChangeKeepsProjectionFreshAndFinancialTotalsStable() {
        val state = mixedState()
        val original = homeLedgerPresentation(state)
        val activity = Activity("latest-payment", "direct", "Payment recorded", at = 3, settlementId = "eur-payment")
        val updated = homeLedgerPresentation(state.copy(activity = state.activity + activity))
        assertEquals(original.overallBalances, updated.overallBalances)
        assertEquals(original.friendItems, updated.friendItems)
        assertEquals(listOf("direct-event", "latest-payment"), updated.nonGroupState.activity.map { it.id })
        assertTrue(updated.nonGroupState.activity.all { it.groupId == NON_GROUP_ID })
        assertEquals("eur-payment", updated.nonGroupState.activity.last().settlementId)
        assertEquals(listOf("direct-event"), original.nonGroupState.activity.map { it.id })
    }

    @Test fun emptyLedgerHasNoFinancialRowsAndValidEmptyNonGroupProjection() {
        val presentation = homeLedgerPresentation(AppState())
        assertTrue(presentation.groupItemsById.isEmpty())
        assertTrue(presentation.friendItems.isEmpty())
        assertTrue(presentation.overallBalances.isEmpty())
        assertTrue(presentation.groupCurrencies.isEmpty())
        assertTrue(presentation.friendCurrencies.isEmpty())
        assertEquals(NON_GROUP_ID, presentation.nonGroup.id)
        assertTrue(presentation.nonGroupState.expenses.isEmpty())
        assertFalse(presentation.nonGroupItem.hasDebt)
        assertFalse(presentation.nonGroupItem.hasReceivable)
    }
}
