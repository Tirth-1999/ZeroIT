package org.fairshare.app.data

import kotlinx.coroutines.flow.StateFlow
import org.fairshare.app.model.*

interface Repository {
    val state: StateFlow<AppState>
    suspend fun load()
    suspend fun refresh()
    suspend fun setProfile(name: String)
    suspend fun setTheme(mode: String)
    suspend fun addFriend(name: String): String
    suspend fun ensureDirectGroup(friendId: String, currency: String): String
    suspend fun ensureNonGroupLedger(memberIds: List<String>, currency: String): String
    suspend fun createGroup(name: String, currency: String, memberNames: List<String>, existingMemberIds: List<String> = emptyList()): String
    suspend fun updateGroupDetails(groupId: String, name: String, theme: GroupTheme)
    suspend fun setSimplifyDebts(groupId: String, enabled: Boolean)
    suspend fun setExpenseDefaults(groupId: String, defaults: ExpenseDefaults?)
    suspend fun removeGroupMember(groupId: String, memberId: String)
    suspend fun setGroupVisibility(groupId: String, visibility: GroupVisibility)
    suspend fun addMembers(groupId: String, existingMemberIds: List<String>, newNames: List<String>)
    suspend fun addMember(groupId: String, name: String)
    suspend fun saveExpense(expense: Expense)
    suspend fun deleteExpense(id: String)
    suspend fun restoreExpense(id: String)
    suspend fun saveSettlement(settlement: Settlement)
    suspend fun deleteSettlement(id: String)
    suspend fun loadDemoScenarios()
    suspend fun loadDemo()
}
