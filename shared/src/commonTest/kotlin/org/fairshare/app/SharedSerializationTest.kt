package org.fairshare.app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*
import org.fairshare.app.model.*
import org.fairshare.app.platform.*

class SharedSerializationTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    @Test fun legacyAndroidSnapshotAndFullRoundTrip() {
        val legacy = """{"profileName":"Taylor","members":[{"id":"self","name":"Taylor"},{"id":"alice","name":"Alice"}],"groups":[{"id":"trip","name":"Trip","currency":"USD","memberIds":["self","alice"],"createdAt":1792454400000}],"expenses":[],"settlements":[],"activity":[],"themeMode":"DARK","onboardingComplete":true}"""
        val state = json.decodeFromString<AppState>(legacy)
        assertEquals(0, state.activityLinkVersion)
        assertEquals(1, json.decodeFromString<AppState>(json.encodeToString(state.copy(activityLinkVersion = 1))).activityLinkVersion)
        assertFalse(state.groups.single().isDirect)
        assertEquals(GroupTheme.CLASSIC, state.groups.single().theme)
        assertTrue(state.groups.single().simplifyDebts)
        assertTrue(state.groups.single().removedMemberIds.isEmpty())
        assertEquals(GroupVisibility.ACTIVE, state.groups.single().visibility)
        assertNull(state.groups.single().expenseDefaults)
        val themed = state.copy(groups = state.groups.map { it.copy(theme = GroupTheme.TRAVEL) })
        assertEquals(themed, json.decodeFromString<AppState>(json.encodeToString(themed)))
        assertEquals("DARK", state.themeMode)
        assertEquals(state, json.decodeFromString<AppState>(json.encodeToString(state)))
        val next = state.copy(expenses = listOf(Expense("lunch", "trip", "Lunch", 101, SELF_ID, mapOf(SELF_ID to 51, "alice" to 50), date = 1792454400000)))
        assertEquals(next, json.decodeFromString<AppState>(json.encodeToString(next)))
    }
    @Test fun legacyTransactionCurrencyAndSinglePayerFallbackRemainReadable() {
        val legacyExpense = """{"id":"e","groupId":"g","title":"Lunch","amountMinor":100,"payerId":"self","owed":{"friend":100},"date":0}"""
        val legacySettlement = """{"id":"s","groupId":"g","fromId":"friend","toId":"self","amountMinor":50,"date":0}"""
        val expense = json.decodeFromString<Expense>(legacyExpense)
        val settlement = json.decodeFromString<Settlement>(legacySettlement)
        assertNull(expense.currency)
        assertTrue(expense.paidBy.isEmpty())
        assertNull(settlement.currency)
        val state = AppState(groups = listOf(Group("g", "Trip", "JPY", listOf(SELF_ID, "friend"))), expenses = listOf(expense), settlements = listOf(settlement))
        assertEquals("JPY", org.fairshare.app.domain.Ledger.expenseCurrency(state, expense))
        assertEquals(mapOf(SELF_ID to 50L, "friend" to -50L), org.fairshare.app.domain.Ledger.balances(state, "g"))
        val multi = state.copy(expenses = listOf(expense.copy(currency = "KWD", paidBy = mapOf(SELF_ID to 25, "friend" to 75))), settlements = listOf(settlement.copy(currency = "KWD")))
        assertEquals(multi, json.decodeFromString<AppState>(json.encodeToString(multi)))
    }

    @Test fun savedGroupControlsRoundTripAlongsideLegacyTransactions() {
        val group = Group("g", "Trip", memberIds = listOf(SELF_ID, "alice", "bob"), simplifyDebts = false,
            removedMemberIds = listOf("bob"), visibility = GroupVisibility.DELETED,
            expenseDefaults = ExpenseDefaults(SELF_ID, SplitMode.PERCENT, listOf(SELF_ID, "alice"), mapOf(SELF_ID to "40", "alice" to "60")))
        val expense = Expense("e", "g", "Original", 100, SELF_ID, mapOf("bob" to 100))
        val state = AppState(groups = listOf(group), expenses = listOf(expense))
        val restored = json.decodeFromString<AppState>(json.encodeToString(state))
        assertEquals(state, restored)
        assertEquals(expense, restored.expenses.single())
        assertEquals(listOf(SELF_ID, "alice"), restored.groups.single().activeMemberIds)
    }

    @Test fun calendarDateFieldsAreUtcButTodayUsesLocalCalendar() {
        val date = parseCalendarDate("2026-10-20")
        assertEquals("2026-10-20", calendarDateInput(date))
        assertEquals("Oct 20, 2026", calendarDateLabel(date))
        assertEquals(0L, date % 86_400_000L)
        assertFailsWith<IllegalArgumentException> { parseCalendarDate("2026-02-30") }
        assertFailsWith<IllegalArgumentException> { parseCalendarDate("20-10-2026") }
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}").matches(todayDate()))
        assertNotEquals(newId(), newId())
    }
}
