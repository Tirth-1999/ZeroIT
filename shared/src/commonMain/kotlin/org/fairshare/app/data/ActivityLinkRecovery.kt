package org.fairshare.app.data

import kotlinx.serialization.json.Json
import org.fairshare.app.model.AppState

/** Recover provenance from a single audited write, never from display prose or matching amounts. */
internal fun recoverActivityLinks(state: AppState, revisions: List<AuditRevision>): AppState {
    val codec = Json { ignoreUnknownKeys = true }
    data class Target(val groupId: String, val expenseId: String? = null, val settlementId: String? = null)
    val candidates = mutableMapOf<String, MutableSet<Target>>()
    val currentEvents = state.activity.associateBy { it.id }
    for (revision in revisions) {
        if (revision.action !in setOf("save_expense", "delete_expense", "restore_expense", "save_settlement", "delete_settlement")) continue
        val before = runCatching { codec.decodeFromString<AppState>(revision.beforeJson) }.getOrNull() ?: continue
        val after = runCatching { codec.decodeFromString<AppState>(revision.afterJson) }.getOrNull() ?: continue
        val beforeEvents = before.activity.map { it.id }.toSet()
        val event = after.activity.filter { it.id !in beforeEvents }.singleOrNull() ?: continue
        val current = currentEvents[event.id] ?: continue
        if (current.expenseId != null || current.settlementId != null || current != event.copy(expenseId = null, settlementId = null)) continue
        val oldExpenses = before.expenses.associateBy { it.id }
        val oldPayments = before.settlements.associateBy { it.id }
        val expenses = after.expenses.filter { oldExpenses[it.id] != it }
        val payments = after.settlements.filter { oldPayments[it.id] != it }
        if (expenses.size + payments.size != 1) continue
        val target = if (expenses.size == 1 && revision.action.endsWith("expense")) {
            val expense = expenses.single()
            if (expense.groupId != event.groupId || state.expenses.none { it.id == expense.id && it.groupId == expense.groupId }) continue
            Target(expense.groupId, expenseId = expense.id)
        } else if (payments.size == 1 && revision.action.endsWith("settlement")) {
            val payment = payments.single()
            if (payment.groupId != event.groupId || state.settlements.none { it.id == payment.id && it.groupId == payment.groupId }) continue
            Target(payment.groupId, settlementId = payment.id)
        } else continue
        candidates.getOrPut(event.id) { mutableSetOf() }.add(target)
    }
    return state.copy(activity = state.activity.map { event ->
        val target = candidates[event.id]?.singleOrNull()
        if (target == null) event else event.copy(expenseId = target.expenseId, settlementId = target.settlementId)
    })
}
