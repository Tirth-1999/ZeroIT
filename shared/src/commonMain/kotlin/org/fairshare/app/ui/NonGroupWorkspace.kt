package org.fairshare.app.ui

import org.fairshare.app.domain.Ledger
import org.fairshare.app.model.*

internal const val NON_GROUP_ID = "__non_group__"

/** A view over existing ledgers, never a persisted merge or a rewrite of transactions. */
internal fun nonGroupProjection(state: AppState): Pair<AppState, Group> {
    val backing = state.groups.filter { it.isNonGroup }
    val ids = backing.map { it.id }.toSet()
    val members = (listOf(SELF_ID) + backing.flatMap { it.memberIds }).distinct()
    val expenses = state.expenses.filter { it.groupId in ids }
    val preference = expenses.firstOrNull { !it.deleted }?.let { Ledger.expenseCurrency(state, it) }
        ?: backing.firstOrNull()?.currency ?: "USD"
    val group = Group(NON_GROUP_ID, "Non-group expenses", preference, members, createdAt = 0, kind = "NONGROUP")
    return state.copy(
        groups = listOf(group),
        expenses = expenses.map { it.copy(groupId = NON_GROUP_ID, currency = Ledger.expenseCurrency(state, it)) },
        settlements = state.settlements.filter { it.groupId in ids }.map { it.copy(groupId = NON_GROUP_ID, currency = Ledger.settlementCurrency(state, it)) },
        activity = state.activity.filter { it.groupId in ids }.map { it.copy(groupId = NON_GROUP_ID) },
    ) to group
}
