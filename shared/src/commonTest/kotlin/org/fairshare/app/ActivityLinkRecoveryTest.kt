package org.fairshare.app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*
import org.fairshare.app.data.AuditRevision
import org.fairshare.app.data.recoverActivityLinks
import org.fairshare.app.model.*

class ActivityLinkRecoveryTest {
    private val json = Json { encodeDefaults = true }
    private val group = Group("g", "Trip", memberIds = listOf(SELF_ID))
    private val expense = Expense("e", "g", "Same title", 5000, SELF_ID, mapOf(SELF_ID to 5000))
    private val event = Activity("a", "g", "An old translated description", at = 99)
    private val empty = AppState(groups = listOf(group))
    private fun revision(before: AppState, after: AppState, action: String = "save_expense") =
        AuditRevision(action = action, at = 100, beforeJson = json.encodeToString(before), afterJson = json.encodeToString(after))

    @Test fun exactAuditedWriteRecoversOneTargetAndPreservesFinancialHistory() {
        val saved = empty.copy(expenses = listOf(expense), activity = listOf(event))
        val current = saved.copy(expenses = listOf(expense.copy(title = "Later title", amountMinor = 6000)))
        val recovered = recoverActivityLinks(current, listOf(revision(empty, saved)))
        assertEquals("e", recovered.activity.single().expenseId)
        assertEquals(current.expenses, recovered.expenses)
        assertEquals(current.settlements, recovered.settlements)
        assertEquals(event, recovered.activity.single().copy(expenseId = null))
        assertEquals(recovered, recoverActivityLinks(recovered, listOf(revision(empty, saved))))
    }

    @Test fun ambiguousUnrelatedMalformedAndExplicitLinksAreNeverGuessed() {
        val saved = empty.copy(expenses = listOf(expense, expense.copy(id = "another")), activity = listOf(event))
        assertEquals(saved, recoverActivityLinks(saved, listOf(revision(empty, saved))))
        val one = saved.copy(expenses = listOf(expense))
        assertEquals(one, recoverActivityLinks(one, listOf(revision(empty, one, "group_details"))))
        assertEquals(one, recoverActivityLinks(one, listOf(revision(empty, one.copy(activity = listOf(event.copy(groupId = "another")))))))
        val explicit = one.copy(activity = listOf(event.copy(expenseId = "existing")))
        assertEquals(explicit, recoverActivityLinks(explicit, listOf(revision(empty, one))))
        assertEquals(one, recoverActivityLinks(one, listOf(AuditRevision(action = "save_expense", at = 1, beforeJson = "broken", afterJson = "{}"))))
        val conflict = one.copy(expenses = listOf(expense.copy(id = "another")))
        assertEquals(saved, recoverActivityLinks(saved, listOf(revision(empty, one), revision(empty, conflict))))
    }

    @Test fun paymentRecoveryUsesItsOwnAuditedRecordAndLeavesInfoEventsAlone() {
        val payment = Settlement("p", "g", SELF_ID, "friend", 500)
        val info = Activity("info", "g", "Updated group settings")
        val saved = empty.copy(settlements = listOf(payment), activity = listOf(event))
        val current = saved.copy(activity = listOf(event, info))
        val recovered = recoverActivityLinks(current, listOf(revision(empty, saved, "save_settlement")))
        assertEquals("p", recovered.activity.first().settlementId)
        assertNull(recovered.activity.first().expenseId)
        assertEquals(info, recovered.activity.last())
        assertEquals(current.settlements, recovered.settlements)
    }
}
