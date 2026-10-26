package org.fairshare.app.ui

import org.fairshare.app.model.AppState

/** A retained tab must never resume an editor whose backing record became unavailable. */
internal fun validTabSessionRoute(
    state: AppState,
    route: String,
    root: String,
    groupId: String,
    expenseId: String,
    settlementId: String,
    friendId: String,
): String {
    if (route == "friend" && state.members.none { it.id == friendId }) return root
    val groupRoutes = setOf("group", "group_settings", "group_defaults", "members", "member", "expense", "detail", "settlement")
    if (route in groupRoutes && groupId != "__non_group__") {
        val group = state.groups.find { it.id == groupId } ?: return root
        if (group.isHidden && route != "group_settings") return "group_settings"
    }
    if (route in setOf("expense", "detail") && expenseId.isNotBlank()) {
        val expense = state.expenses.find { it.id == expenseId } ?: return root
        if (route == "expense" && expense.deleted) return "detail"
    }
    if (route == "settlement" && settlementId.isNotBlank() &&
        state.settlements.none { it.id == settlementId && !it.deleted }) return root
    return route
}
