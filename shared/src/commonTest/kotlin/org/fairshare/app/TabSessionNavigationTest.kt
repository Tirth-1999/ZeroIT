package org.fairshare.app

import kotlin.test.*
import org.fairshare.app.model.*
import org.fairshare.app.ui.validTabSessionRoute

class TabSessionNavigationTest {
    private val group = Group("g", "Trip", memberIds = listOf(SELF_ID))
    private val expense = Expense("e", "g", "Dinner", 100, SELF_ID, mapOf(SELF_ID to 100))
    private val payment = Settlement("p", "g", "friend", SELF_ID, 100)
    private val state = AppState(groups = listOf(group), expenses = listOf(expense), settlements = listOf(payment))
    private fun restored(route: String, ledger: AppState = state, expenseId: String = "e", paymentId: String = "p", groupId: String = "g") =
        validTabSessionRoute(ledger, route, "activity", groupId, expenseId, paymentId, "friend")

    @Test fun validDetailAndGroupContextsRemainUnchanged() {
        listOf("group", "group_settings", "group_defaults", "members", "member", "expense", "detail", "settlement").forEach {
            assertEquals(it, restored(it))
        }
        assertEquals("expense", restored("expense", expenseId = "", groupId = "__non_group__"))
    }

    @Test fun hiddenAndMissingTargetsCannotResumeEditors() {
        assertEquals("activity", restored("group", ledger = state.copy(groups = emptyList())))
        assertEquals("group_settings", restored("expense", ledger = state.copy(groups = listOf(group.copy(visibility = GroupVisibility.DELETED)))))
        assertEquals("activity", restored("detail", ledger = state.copy(expenses = emptyList())))
        assertEquals("detail", restored("expense", ledger = state.copy(expenses = listOf(expense.copy(deleted = true)))))
        assertEquals("detail", restored("detail", ledger = state.copy(expenses = listOf(expense.copy(deleted = true)))))
        assertEquals("activity", restored("settlement", ledger = state.copy(settlements = listOf(payment.copy(deleted = true)))))
        assertEquals("activity", restored("friend"))
    }
}
