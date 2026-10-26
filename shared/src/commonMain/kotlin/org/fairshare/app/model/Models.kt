package org.fairshare.app.model

import kotlinx.serialization.Serializable
import org.fairshare.app.platform.nowMillis

const val SELF_ID = "self"
/** Shared storage/editor limit; transaction totals above this are never persisted. */
const val MAX_TRANSACTION_MINOR = 1_000_000_000_000L
@Serializable
data class Member(val id: String, val name: String)
@Serializable
data class Group(val id: String, val name: String, val currency: String = "USD", val memberIds: List<String>, val createdAt: Long = nowMillis(), val kind: String? = null, val theme: GroupTheme = GroupTheme.CLASSIC, val simplifyDebts: Boolean = true,
    val removedMemberIds: List<String> = emptyList(), val visibility: GroupVisibility = GroupVisibility.ACTIVE,
    val expenseDefaults: ExpenseDefaults? = null) {
    // Missing markers in older Android snapshots remain null and represent regular groups.
    val activeMemberIds: List<String> get() = memberIds.filterNot { it in removedMemberIds }
    val isHidden: Boolean get() = visibility != GroupVisibility.ACTIVE
    val isDirect: Boolean get() = kind == "DIRECT"
    val isNonGroup: Boolean get() = isDirect || kind == "NONGROUP"
}
/** Missing creation timestamps use the original expense date without inventing historical precision. */
/** Null currency and empty paidBy preserve pre-migration group-currency/single-payer records. */
@Serializable
data class Expense(val id: String, val groupId: String, val title: String, val amountMinor: Long, val payerId: String, val owed: Map<String, Long>, val date: Long = nowMillis(), val notes: String = "", val deleted: Boolean = false, val currency: String? = null, val paidBy: Map<String, Long> = emptyMap(), val createdAt: Long = date)
/** Payments settle one currency only; null is the legacy group-currency marker. */
@Serializable
data class Settlement(val id: String, val groupId: String, val fromId: String, val toId: String, val amountMinor: Long, val date: Long = nowMillis(), val deleted: Boolean = false, val currency: String? = null, val createdAt: Long = date)
@Serializable
data class Activity(val id: String, val groupId: String, val message: String, val at: Long = nowMillis(), val expenseId: String? = null, val settlementId: String? = null)
@Serializable
data class AppState(val profileName: String = "You", val members: List<Member> = listOf(Member(SELF_ID, "You")), val groups: List<Group> = emptyList(), val expenses: List<Expense> = emptyList(), val settlements: List<Settlement> = emptyList(), val activity: List<Activity> = emptyList(), val themeMode: String = "SYSTEM", val onboardingComplete: Boolean = false, val activityLinkVersion: Int = 0)
@Serializable
data class ExpenseDefaults(val payerId: String = SELF_ID, val splitMode: SplitMode = SplitMode.EQUAL, val participantIds: List<String> = emptyList(), val values: Map<String, String> = emptyMap(), val currency: String? = null)
enum class GroupVisibility { ACTIVE, LEFT, DELETED }
enum class GroupTheme { CLASSIC, TRAVEL, HOME, DINING, CELEBRATION, NATURE }
enum class SplitMode { EQUAL, EXACT, PERCENT, SHARES, ADJUSTMENT }
@Serializable
data class Transfer(val fromId: String, val toId: String, val amountMinor: Long)
