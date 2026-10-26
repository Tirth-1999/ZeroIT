package org.fairshare.app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*
import org.fairshare.app.model.*
import org.fairshare.app.ui.editableActivitySettlement
import org.fairshare.app.ui.editableActivityExpense
import org.fairshare.app.ui.expensesNewestAddedFirst
import org.fairshare.app.ui.restorableActivityExpense

class ActivityHistoryTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val group = Group("g", "Trip", memberIds = listOf(SELF_ID))
    private val expense = Expense("e", "g", "Lunch", 5000, SELF_ID, mapOf(SELF_ID to 5000), date = 1000, createdAt = 9000)

    @Test fun creationOrderIsIndependentOfExpenseDateAndStableForTies() {
        val olderAdded = expense.copy(id = "older", date = 8000, createdAt = 1000)
        val laterAdded = expense.copy(id = "later", date = 0, createdAt = 2000)
        val latestAtSameMillisecond = expense.copy(id = "latest", date = 10, createdAt = 2000)
        assertEquals(listOf("latest", "later", "older"), expensesNewestAddedFirst(listOf(olderAdded, laterAdded, latestAtSameMillisecond)).map { it.id })
        assertEquals(0L, laterAdded.date)
    }

    @Test fun oldSnapshotsKeepOriginalDatesAndUnlinkedHistoryRemainsReadOnly() {
        val oldExpense = json.decodeFromString<Expense>("""{"id":"e","groupId":"g","title":"Lunch","amountMinor":5000,"payerId":"self","owed":{"self":5000},"date":1234}""")
        assertEquals(1234L, oldExpense.createdAt)
        val oldPayment = json.decodeFromString<Settlement>("""{"id":"p","groupId":"g","fromId":"a","toId":"self","amountMinor":5000,"date":1234}""")
        assertEquals(1234L, oldPayment.createdAt)
        val oldActivity = json.decodeFromString<Activity>("""{"id":"a","groupId":"g","message":"Added Lunch e","at":2345}""")
        assertNull(oldActivity.expenseId)
        assertNull(oldActivity.settlementId)
        assertNull(editableActivityExpense(AppState(groups = listOf(group), expenses = listOf(oldExpense)), oldActivity))
    }

    @Test fun explicitLinkRoundTripsButDeletedHiddenAndWrongGroupTargetsAreReadOnly() {
        val linked = Activity("a", "g", "Added Lunch", at = 9000, expenseId = "e")
        val state = AppState(groups = listOf(group), expenses = listOf(expense), activity = listOf(linked))
        assertEquals(state, json.decodeFromString<AppState>(json.encodeToString(state)))
        assertEquals(expense, editableActivityExpense(state, linked))
        assertNull(editableActivityExpense(state.copy(expenses = listOf(expense.copy(deleted = true))), linked))
        assertNull(editableActivityExpense(state.copy(groups = listOf(group.copy(visibility = GroupVisibility.DELETED))), linked))
        assertNull(editableActivityExpense(state.copy(groups = listOf(group.copy(visibility = GroupVisibility.LEFT))), linked))
        assertNull(editableActivityExpense(state, linked.copy(groupId = "other")))
        assertNull(editableActivityExpense(state, linked.copy(expenseId = "missing")))
        assertEquals(linked, state.activity.single())
    }

    @Test fun paymentLinksRoundTripAndNeverGuessOrTargetHiddenDeletedOrMixedRecords() {
        val payment = Settlement("payment", "g", "alice", SELF_ID, 500, date = 1000, createdAt = 9000)
        val linked = Activity("payment-event", "g", "Recorded external payment", at = 9000, settlementId = payment.id)
        val state = AppState(groups = listOf(group), settlements = listOf(payment), expenses = listOf(expense), activity = listOf(linked))
        assertEquals(state, json.decodeFromString<AppState>(json.encodeToString(state)))
        assertEquals(payment, editableActivitySettlement(state, linked))
        assertNull(editableActivityExpense(state, linked))
        assertNull(editableActivitySettlement(state, linked.copy(settlementId = null)))
        assertNull(editableActivitySettlement(state, linked.copy(expenseId = expense.id)))
        assertNull(editableActivityExpense(state, linked.copy(expenseId = expense.id)))
        assertNull(editableActivitySettlement(state, linked.copy(groupId = "other")))
        assertNull(editableActivitySettlement(state.copy(groups = listOf(group.copy(visibility = GroupVisibility.LEFT))), linked))
        assertNull(editableActivitySettlement(state.copy(settlements = listOf(payment.copy(deleted = true))), linked))
    }

    @Test fun deterministicDemoIdsResolveWithoutUsingProseAndRetainTargetGuards() {
        val demo = expense.copy(id = "demo-scenarios-trip-expense-1")
        val event = Activity("${demo.id}-added", group.id, "An unrelated translated description", at = 2)
        val state = AppState(groups = listOf(group), expenses = listOf(demo))
        assertEquals(demo, editableActivityExpense(state, event))
        assertNull(editableActivityExpense(state, event.copy(groupId = "other")))
        assertNull(editableActivityExpense(state, event.copy(expenseId = "explicit-missing")))
        assertNull(editableActivityExpense(state.copy(expenses = listOf(demo.copy(deleted = true))), event))
        val ordinary = expense.copy(id = "ordinary")
        assertNull(editableActivityExpense(state.copy(expenses = listOf(ordinary)), event.copy(id = "ordinary-added")))
        val payment = Settlement("demo-scenarios-trip-payment-1", group.id, "alice", SELF_ID, 500)
        val paid = Activity("${payment.id}-recorded", group.id, "Translated payment description")
        assertEquals(payment, editableActivitySettlement(state.copy(settlements = listOf(payment)), paid))
        assertNull(editableActivitySettlement(state.copy(settlements = listOf(payment.copy(deleted = true))), paid))
    }

    @Test fun restoreOnlyTargetsTheDeletedExpenseInItsVisibleGroup() {
        val deleted = expense.copy(deleted = true)
        val event = Activity("deletion", group.id, "Deleted Lunch", expenseId = expense.id)
        val state = AppState(groups = listOf(group), expenses = listOf(deleted))
        assertEquals(deleted, restorableActivityExpense(state, event))
        assertNull(restorableActivityExpense(state, event.copy(settlementId = "payment")))
        assertNull(restorableActivityExpense(state, event.copy(expenseId = null)))
        assertNull(restorableActivityExpense(state, event.copy(groupId = "other")))
        assertNull(restorableActivityExpense(state, event.copy(message = "Added Lunch")))
        assertNull(restorableActivityExpense(state.copy(expenses = listOf(expense)), event))
        assertNull(restorableActivityExpense(state.copy(groups = listOf(group.copy(visibility = GroupVisibility.DELETED))), event))
    }

}
