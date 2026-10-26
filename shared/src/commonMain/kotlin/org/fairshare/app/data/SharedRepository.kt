package org.fairshare.app.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fairshare.app.model.*
import org.fairshare.app.domain.Ledger
import org.fairshare.app.platform.*
import org.fairshare.app.math.BigInteger

open class SharedRepository(private val database: LocalDatabase) : Repository {
    private val jsonCodec = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private val mutex = Mutex()
    private val current = MutableStateFlow(AppState())
    override val state: StateFlow<AppState> = current.asStateFlow()
    private var loaded = false
    private var loadedJson: String? = null

    override suspend fun load() = mutex.withLock { withContext(Dispatchers.Default) { loadLocked() } }

    override suspend fun refresh() = mutex.withLock {
        withContext(Dispatchers.Default) { loadLocked(force = true) }
    }

    private suspend fun loadLocked(force: Boolean = false) {
        if (loaded && !force) return
        val stored = database.stateDao().read()
        val value = if (stored == null) AppState(activityLinkVersion = ACTIVITY_LINK_VERSION) else try {
            val objectValue = jsonCodec.parseToJsonElement(stored.json).jsonObject
            require(STATE_FIELDS.all { objectValue.containsKey(it) && objectValue[it] != JsonNull }) { "Saved state is incomplete." }
            jsonCodec.decodeFromString<AppState>(stored.json).also(::validateState)
        } catch (failure: Exception) {
            throw IllegalStateException("Saved data could not be read. It has been preserved; please retry or export the database.", failure)
        }
        // Older snapshots omit this marker. Audit snapshots can be large; inspect them
        // only once, even when informational events intentionally have no transaction.
        val needsLinkMigration = stored != null && value.activityLinkVersion < ACTIVITY_LINK_VERSION
        val recovered = if (needsLinkMigration && value.activity.any { it.expenseId == null && it.settlementId == null })
            recoverActivityLinks(value, database.stateDao().transactionRevisions()) else value
        val repaired = if (needsLinkMigration) recovered.copy(activityLinkVersion = ACTIVITY_LINK_VERSION) else recovered
        val persistedJson = if (repaired != value) {
            validateState(repaired)
            val repairedJson = jsonCodec.encodeToString(repaired)
            database.stateDao().commitIfUnchanged(stored?.json, StateRow(json = repairedJson), AuditRevision(
                action = if (recovered.activity != value.activity) "repair_activity_links" else "check_activity_links", at = nowMillis(),
                beforeJson = requireNotNull(stored).json, afterJson = repairedJson,
            ))
            repairedJson
        } else stored?.json
        current.value = repaired
        loadedJson = persistedJson
        loaded = true
    }

    private suspend fun change(action: String, update: (AppState) -> AppState) = mutex.withLock {
        withContext(Dispatchers.Default) {
            loadLocked()
            val before = current.value
            val after = update(before)
            if (before == after) return@withContext
            validateState(after)
            // Historical edits and debt-mode changes must not reopen debt for people
            // who have been removed from future expenses and payment pickers.
            after.groups.forEach { group ->
                require(group.removedMemberIds.none { Ledger.memberHasDebt(after, group.id, it) }) { "This change would reopen debt for a removed member. Add them back to the group first." }
            }
            val json = jsonCodec.encodeToString(after)
            database.stateDao().commitIfUnchanged(loadedJson, StateRow(json = json), AuditRevision(action = action, at = nowMillis(), beforeJson = jsonCodec.encodeToString(before), afterJson = json))
            current.value = after
            loadedJson = json
        }
    }

    override suspend fun setProfile(name: String) = change("profile") { s ->
        val clean = cleanName(name)
        s.copy(profileName = clean, onboardingComplete = true, members = s.members.map { if (it.id == SELF_ID) it.copy(name = clean) else it })
    }

    override suspend fun setTheme(mode: String) = change("theme") { s ->
        require(mode in THEMES) { "Choose System, Light or Dark appearance." }
        s.copy(themeMode = mode)
    }

    override suspend fun addFriend(name: String): String {
        var friendId = ""
        change("add_friend") { s ->
            val clean = cleanName(name)
            require(!s.memberName(SELF_ID).equals(clean, true)) { "Your profile is already included. Enter your friend's name." }
            val existing = s.members.firstOrNull { it.name.equals(clean, true) }
            val friend = existing ?: Member(id(), clean)
            friendId = friend.id
            if (existing != null) s else s.copy(members = s.members + friend)
        }
        return friendId
    }

    override suspend fun ensureDirectGroup(friendId: String, currency: String): String {
        var groupId = ""
        change("create_direct_ledger") { s ->
            require(friendId != SELF_ID) { "Choose a friend other than yourself." }
            val friend = requireNotNull(s.members.find { it.id == friendId }) { "Friend not found." }
            val cleanCurrency = validCurrency(currency)
            val existing = s.groups.firstOrNull { it.isDirect && friendId in it.memberIds && it.currency == cleanCurrency }
            if (existing != null) {
                groupId = existing.id
                s
            } else {
                val group = Group(id(), friend.name, cleanCurrency, listOf(SELF_ID, friendId), kind = "DIRECT")
                groupId = group.id
                s.copy(groups = s.groups + group)
            }
        }
        return groupId
    }

    override suspend fun ensureNonGroupLedger(memberIds: List<String>, currency: String): String {
        var groupId = ""
        change("create_non_group_ledger") { s ->
            require(memberIds.isNotEmpty() && memberIds.distinct().size == memberIds.size && SELF_ID in memberIds) { "Select unique people including yourself." }
            require(memberIds.size <= 100 && memberIds.all { member -> s.members.any { it.id == member } }) { "Select saved people." }
            val cleanCurrency = validCurrency(currency)
            val people = memberIds.toSet()
            val existing = s.groups.firstOrNull { it.isNonGroup && it.memberIds.toSet() == people && it.currency == cleanCurrency }
            if (existing != null) {
                groupId = existing.id
                s
            } else {
                val ordered = listOf(SELF_ID) + memberIds.filter { it != SELF_ID }.sorted()
                val group = Group(id(), if (ordered.size == 2) s.memberName(ordered.last()) else "Non-group expenses", cleanCurrency, ordered, kind = if (ordered.size == 2) "DIRECT" else "NONGROUP")
                groupId = group.id
                s.copy(groups = s.groups + group)
            }
        }
        return groupId
    }

    override suspend fun createGroup(name: String, currency: String, memberNames: List<String>, existingMemberIds: List<String>): String {
        val groupId = id()
        change("create_group") { s ->
            val additions = selectedMembers(s, listOf(SELF_ID), existingMemberIds, memberNames)
            val group = Group(groupId, cleanName(name), validCurrency(currency), listOf(SELF_ID) + additions.map { it.id }, kind = "GROUP")
            s.copy(members = s.members + additions.filter { it.id !in existingMemberIds }, groups = s.groups + group)
                .record(groupId, "Created group ${group.name} (${group.currency}) with ${group.memberIds.size} members")
        }
        return groupId
    }

    override suspend fun updateGroupDetails(groupId: String, name: String, theme: GroupTheme) = change("group_details") { s ->
        val original = s.group(groupId)
        require(!original.isHidden) { "Restore this group before changing its settings." }
        require(!original.isNonGroup) { "Choose a regular group to customize." }
        val updated = original.copy(name = cleanName(name), theme = theme)
        if (updated == original) s else s.copy(groups = s.groups.map { if (it.id == groupId) updated else it })
            .record(groupId, "Updated group settings for ${updated.name}")
    }

    override suspend fun setSimplifyDebts(groupId: String, enabled: Boolean) = change("simplify_debts") { s ->
        val group = s.group(groupId)
        require(!group.isHidden && !group.isNonGroup) { "Choose an active group to change debt simplification." }
        if (group.simplifyDebts == enabled) s else s.copy(groups = s.groups.map { if (it.id == groupId) it.copy(simplifyDebts = enabled) else it })
            .record(groupId, if (enabled) "Enabled simplified debts" else "Disabled simplified debts")
    }

    override suspend fun setExpenseDefaults(groupId: String, defaults: ExpenseDefaults?) = change("personal_expense_defaults") { s ->
        val group = s.group(groupId)
        require(!group.isHidden && !group.isNonGroup) { "Choose an active group to save personal expense defaults." }
        defaults?.let { validateDefaults(it, group) }
        s.copy(groups = s.groups.map { if (it.id == groupId) it.copy(expenseDefaults = defaults) else it })
    }

    override suspend fun removeGroupMember(groupId: String, memberId: String) = change("remove_group_member") { s ->
        val group = s.group(groupId)
        require(!group.isHidden && !group.isNonGroup) { "Choose an active group to remove a member." }
        require(memberId != SELF_ID && memberId in group.activeMemberIds) { "Choose another active group member." }
        require(!Ledger.memberHasDebt(s, groupId, memberId)) { "Settle this member's balances in every currency before removing them." }
        val defaults = group.expenseDefaults?.takeUnless { it.payerId == memberId || memberId in it.participantIds }
        s.copy(groups = s.groups.map { if (it.id == groupId) it.copy(removedMemberIds = it.removedMemberIds + memberId, expenseDefaults = defaults) else it })
            .record(groupId, "Removed ${s.memberName(memberId)} from future expenses; history retained")
    }

    override suspend fun setGroupVisibility(groupId: String, visibility: GroupVisibility) = change("group_visibility") { s ->
        val group = s.group(groupId)
        require(!group.isNonGroup) { "Non-group expenses cannot be left or deleted as a group." }
        if (group.visibility == visibility) return@change s
        if (visibility == GroupVisibility.LEFT) {
            require(!Ledger.memberHasDebt(s, groupId, SELF_ID)) { "Settle your balances in every currency before leaving." }
        } else if (visibility == GroupVisibility.DELETED) {
            require(group.memberIds.none { Ledger.memberHasDebt(s, groupId, it) }) { "Settle every member's balances in every currency before deleting this group." }
        }
        s.copy(groups = s.groups.map { if (it.id == groupId) it.copy(visibility = visibility) else it })
            .record(groupId, when (visibility) {
                GroupVisibility.ACTIVE -> "Restored group on this device"
                GroupVisibility.LEFT -> "Left group on this device; history retained"
                GroupVisibility.DELETED -> "Deleted group from active lists on this device; history retained"
            })
    }

    override suspend fun addMember(groupId: String, name: String) = addMembers(groupId, emptyList(), listOf(name))

    override suspend fun addMembers(groupId: String, existingMemberIds: List<String>, newNames: List<String>) = change("add_members") { s ->
        val group = s.group(groupId)
        require(!group.isNonGroup) { "A friend expense includes only you and that friend. Create a group to add more people." }
        require(!group.isHidden) { "Restore this group before adding members." }
        val additions = selectedMembers(s, group.activeMemberIds, existingMemberIds, newNames)
        val historicalIds = (group.memberIds + additions.map { it.id }).distinct()
        require(historicalIds.size <= 100) { "A group can have at most 100 historical members." }
        if (additions.isEmpty()) s else s.copy(
            members = s.members + additions.filter { it.id !in existingMemberIds },
            groups = s.groups.map { if (it.id == groupId) it.copy(memberIds = historicalIds, removedMemberIds = it.removedMemberIds.filterNot { removed -> additions.any { member -> member.id == removed } }) else it }
        ).record(groupId, "Added ${if (additions.size == 1) "member" else "members"} ${additions.joinToString { it.name }}")
    }

    /** Resolve the entire selection before changing state so an invalid batch never adds a subset. */
    private fun selectedMembers(s: AppState, currentIds: List<String>, existingIds: List<String>, newNames: List<String>): List<Member> {
        require(existingIds.distinct().size == existingIds.size) { "Each person can only be selected once." }
        require(SELF_ID !in existingIds) { "You are already included in the group." }
        require(existingIds.none { it in currentIds }) { "A selected person is already in the group." }
        val selected = existingIds.map { memberId ->
            requireNotNull(s.members.find { it.id == memberId }) { "A selected person could not be found." }
        }
        val names = newNames.map(::cleanName)
        val candidateNames = selected.map { it.name } + names
        require(candidateNames.distinctBy { it.lowercase() }.size == candidateNames.size) { "Member names must be unique." }
        val currentNames = currentIds.map { s.memberName(it).lowercase() }.toSet()
        require(candidateNames.none { it.lowercase() in currentNames }) { "A member with this name is already in the group." }
        require(names.none { newName -> s.members.any { it.name.equals(newName, true) } }) { "This person is already saved. Select the existing person instead." }
        require(currentIds.size + candidateNames.size <= 100) { "A group can have at most 100 members." }
        return selected + names.map { Member(id(), it) }
    }

    override suspend fun saveExpense(expense: Expense) = change("save_expense") { s ->
        val previous = s.expenses.find { it.id == expense.id }
        val normalized = expense.copy(title = expense.title.trim(), notes = expense.notes.trim(), createdAt = previous?.createdAt ?: nowMillis())
        val group = s.group(expense.groupId)
        require(!group.isHidden) { "Restore this group before changing expenses." }
        val allowed = group.activeMemberIds.toSet() + (previous?.let { it.owed.keys + Ledger.expensePayers(it).keys + it.payerId } ?: emptySet())
        require(expense.payerId in allowed && expense.owed.keys.all { it in allowed } && Ledger.expensePayers(expense).keys.all { it in allowed }) { "Choose active members for new expenses." }
        require(previous == null || previous.groupId == expense.groupId) { "An expense cannot move between groups." }
        require(!normalized.deleted && previous?.deleted != true) { "Restore a deleted expense before editing it." }
        if (previous == normalized) return@change s
        val updated = s.copy(expenses = if (previous == null) s.expenses + normalized else s.expenses.map { if (it.id == normalized.id) normalized else it })
        updated.record(expense.groupId, if (previous == null) "Added ${s.expenseDescription(normalized)}" else "Edited expense: ${s.expenseDescription(previous)} → ${s.expenseDescription(normalized)}", expenseId = normalized.id)
    }

    override suspend fun deleteExpense(id: String) = change("delete_expense") { s ->
        val expense = requireNotNull(s.expenses.find { it.id == id }) { "Expense not found." }
        require(!s.group(expense.groupId).isHidden) { "Restore this group before changing expenses." }
        if (expense.deleted) s else s.copy(expenses = s.expenses.map { if (it.id == id) it.copy(deleted = true) else it })
            .record(expense.groupId, "Deleted ${s.expenseDescription(expense)}", expenseId = expense.id)
    }

    override suspend fun restoreExpense(id: String) = change("restore_expense") { s ->
        val expense = requireNotNull(s.expenses.find { it.id == id }) { "Expense not found." }
        require(!s.group(expense.groupId).isHidden) { "Restore this group before changing expenses." }
        if (!expense.deleted) s else s.copy(expenses = s.expenses.map { if (it.id == id) it.copy(deleted = false) else it })
            .record(expense.groupId, "Restored ${s.expenseDescription(expense)}", expenseId = expense.id)
    }

    override suspend fun saveSettlement(settlement: Settlement) = change("save_settlement") { s ->
        val previous = s.settlements.find { it.id == settlement.id }
        val normalized = settlement.copy(createdAt = previous?.createdAt ?: nowMillis())
        val group = s.group(settlement.groupId)
        require(!group.isHidden) { "Restore this group before changing payments." }
        val allowed = group.activeMemberIds.toSet() + (previous?.let { setOf(it.fromId, it.toId) } ?: emptySet())
        require(settlement.fromId in allowed && settlement.toId in allowed) { "Choose active members for new payments." }
        require(previous == null || previous.groupId == settlement.groupId) { "A settlement cannot move between groups." }
        require(!settlement.deleted && previous?.deleted != true) { "Deleted settlements cannot be edited." }
        validateSettlement(settlement, s)
        if (previous == normalized) return@change s
        val beforePayment = s.copy(settlements = s.settlements.filterNot { it.id == settlement.id })
        val currency = Ledger.settlementCurrency(s, settlement)
        if (group.simplifyDebts) {
            val balances = Ledger.balances(beforePayment, settlement.groupId, currency)
            val debtorBalance = balances.getValue(settlement.fromId)
            val receivable = balances.getValue(settlement.toId)
            val fitsNetBalances = debtorBalance < 0 && receivable > 0 && BigInteger.valueOf(settlement.amountMinor) <= -BigInteger.valueOf(debtorBalance) && settlement.amountMinor <= receivable
            // The simplifier can retain direct routing when it uses fewer transfers.
            // Those configured suggestions remain valid even for net-zero intermediaries.
            val suggestedDebt = Ledger.transfers(beforePayment, settlement.groupId, currency)
                .firstOrNull { it.fromId == settlement.fromId && it.toId == settlement.toId }?.amountMinor ?: 0L
            require(fitsNetBalances || settlement.amountMinor <= suggestedDebt) { "This exceeds the outstanding debt between these balances." }
        } else {
            val debt = Ledger.directTransfers(beforePayment, settlement.groupId, currency)
                .firstOrNull { it.fromId == settlement.fromId && it.toId == settlement.toId }?.amountMinor ?: 0L
            require(settlement.amountMinor <= debt) { "This exceeds the outstanding direct debt between these members." }
        }
        s.copy(settlements = if (previous == null) s.settlements + normalized else s.settlements.map { if (it.id == settlement.id) normalized else it })
            .record(settlement.groupId, if (previous == null) "Recorded external payment: ${s.settlementDescription(settlement)}" else "Edited external payment: ${s.settlementDescription(previous)} → ${s.settlementDescription(settlement)}", settlementId = settlement.id)
    }

    override suspend fun deleteSettlement(id: String) = change("delete_settlement") { s ->
        val settlement = requireNotNull(s.settlements.find { it.id == id }) { "Settlement not found." }
        require(!s.group(settlement.groupId).isHidden) { "Restore this group before changing payments." }
        if (settlement.deleted) s else s.copy(settlements = s.settlements.map { if (it.id == id) it.copy(deleted = true) else it })
            .record(settlement.groupId, "Deleted external payment: ${s.settlementDescription(settlement)}", settlementId = settlement.id)
    }

    override suspend fun loadDemoScenarios() = change("load_demo_scenarios") { it.withDemoScenarios() }

    override suspend fun loadDemo() = change("load_demo") { s ->
        if (s.groups.any { it.id == DEMO_ID }) s else {
            val alice = Member("demo-alice", "Alice")
            val bob = Member("demo-bob", "Bob")
            val group = Group(DEMO_ID, "Demo · Weekend", "USD", listOf(SELF_ID, alice.id, bob.id), kind = "GROUP")
            val today = parseCalendarDate(todayDate())
            val expense = Expense("demo-dinner", DEMO_ID, "Demo dinner", 3000, SELF_ID, group.memberIds.associateWith { 1000L }, date = today)
            s.copy(members = s.members + listOf(alice, bob), groups = s.groups + group, expenses = s.expenses + expense)
                .record(DEMO_ID, "Created optional demo: $30 dinner split equally among three local members")
        }
    }

    private fun validateState(s: AppState) {
        require(s.activityLinkVersion in 0..ACTIVITY_LINK_VERSION) { "Saved data uses an unsupported activity-link version." }
        cleanName(s.profileName)
        require(s.themeMode in THEMES) { "Invalid saved appearance." }
        uniqueIds(s.members.map { it.id }); uniqueIds(s.groups.map { it.id }); uniqueIds(s.expenses.map { it.id }); uniqueIds(s.settlements.map { it.id }); uniqueIds(s.activity.map { it.id })
        require(s.members.single { it.id == SELF_ID }.name == s.profileName) { "Local profile is inconsistent." }
        s.members.forEach { cleanName(it.name) }
        s.groups.forEach { g ->
            cleanName(g.name); validCurrency(g.currency); validDate(g.createdAt)
            require(g.kind == null || g.kind in setOf("GROUP", "DIRECT", "NONGROUP")) { "Invalid ledger type." }
            require(g.memberIds.size in 1..100 && g.memberIds.count { it == SELF_ID } == 1) { "A group needs your local profile and at most 100 members." }
            require(!g.isDirect || g.memberIds.size == 2) { "A friend ledger needs exactly two people." }
            require(g.removedMemberIds.distinct().size == g.removedMemberIds.size && g.removedMemberIds.all { it in g.memberIds && it != SELF_ID }) { "Invalid removed membership." }
            require(!g.isNonGroup || (g.removedMemberIds.isEmpty() && !g.isHidden && g.expenseDefaults == null)) { "Invalid non-group settings." }
            g.expenseDefaults?.let { validateDefaults(it, g) }
            require(g.memberIds.distinct().size == g.memberIds.size && g.memberIds.all { member -> s.members.any { it.id == member } }) { "Invalid group membership." }
        }
        val directKeys = s.groups.filter { it.isDirect }.map { it.memberIds.single { member -> member != SELF_ID } to it.currency }
        require(directKeys.distinct().size == directKeys.size) { "Duplicate friend ledger for this currency." }
        s.expenses.forEach { e ->
            require(e.title.isNotBlank() && e.title.length <= 160) { "Enter a description of at most 160 characters." }
            require(e.notes.length <= 2000) { "Notes must be at most 2,000 characters." }
            validMoney(e.amountMinor); validDate(e.date); validDate(e.createdAt); e.currency?.let(::validCurrency)
            val group = s.group(e.groupId)
            require(e.payerId in group.memberIds) { "The payer must belong to this group." }
            require(e.owed.isNotEmpty() && e.owed.keys.all { it in group.memberIds } && e.owed.values.all { it in 0..MAX_MONEY }) { "Choose valid group participants and nonnegative shares." }
            require(e.owed.values.fold(BigInteger.ZERO) { total, value -> total + BigInteger.valueOf(value) } == BigInteger.valueOf(e.amountMinor)) { "Shares must add up to the expense total." }
            val paid = Ledger.expensePayers(e)
            require(paid.keys.all { it in group.memberIds } && paid.values.all { it in 0..MAX_MONEY }) { "Choose valid group payers and nonnegative payments." }
            require(paid.values.fold(BigInteger.ZERO) { total, value -> total + BigInteger.valueOf(value) } == BigInteger.valueOf(e.amountMinor)) { "Payments must add up to the expense total." }
        }
        s.settlements.forEach { validateSettlement(it, s) }
        s.activity.forEach { activity ->
            require(s.groups.any { g -> g.id == activity.groupId }); validDate(activity.at)
            require(activity.expenseId == null || activity.settlementId == null) { "An activity can link to only one transaction." }
            activity.settlementId?.let { settlementId ->
                require(s.settlements.any { it.id == settlementId && it.groupId == activity.groupId }) { "Invalid payment activity link." }
            }
            activity.expenseId?.let { expenseId ->
                require(s.expenses.any { it.id == expenseId && it.groupId == activity.groupId }) { "Invalid expense activity link." }
            }
        }
        s.groups.forEach { group -> Ledger.groupCurrencies(s, group.id).forEach { Ledger.balances(s, group.id, it) } }
    }

    private fun validateDefaults(defaults: ExpenseDefaults, group: Group) {
        require(defaults.payerId in group.activeMemberIds) { "Choose an active default payer." }
        val people = defaults.participantIds
        require(people.isNotEmpty() && people.distinct().size == people.size && people.all { it in group.activeMemberIds }) { "Choose unique active default participants." }
        require(defaults.values.keys.all { it in people }) { "Default split values must belong to selected participants." }
        defaults.currency?.let(::validCurrency)
        when (defaults.splitMode) {
            SplitMode.EQUAL -> require(defaults.values.isEmpty()) { "Equal splits do not need custom values." }
            SplitMode.PERCENT, SplitMode.SHARES -> Ledger.split(10_000, people, defaults.splitMode, defaults.values, defaults.currency ?: group.currency)
            SplitMode.EXACT, SplitMode.ADJUSTMENT -> {
                val currency = requireNotNull(defaults.currency) { "Choose a currency for fixed default amounts." }
                val amounts = people.map { Ledger.parseMoney(defaults.values[it].orEmpty().ifBlank { "0" }, currency) }
                require(amounts.all { it in -MAX_MONEY..MAX_MONEY }) { "Default amounts exceed the supported range." }
                if (defaults.splitMode == SplitMode.EXACT) {
                    require(amounts.all { it >= 0 }) { "Exact default amounts cannot be negative." }
                    val total = amounts.fold(BigInteger.ZERO) { sum, amount -> sum + BigInteger.valueOf(amount) }
                    require(total > BigInteger.ZERO && total <= BigInteger.valueOf(MAX_MONEY)) { "Exact default amounts must have a valid positive total." }
                }
            }
        }
    }

    private fun validateSettlement(value: Settlement, s: AppState) {
        validMoney(value.amountMinor); validDate(value.date); validDate(value.createdAt); value.currency?.let(::validCurrency)
        val group = s.group(value.groupId)
        require(value.fromId != value.toId && value.fromId in group.memberIds && value.toId in group.memberIds) { "Choose two different group members." }
    }

    private fun AppState.expenseDescription(e: Expense): String {
        val currency = Ledger.expenseCurrency(this, e)
        val payers = Ledger.expensePayers(e).filterValues { it > 0L }
        val payerSummary = when (payers.size) {
            0 -> ""
            1 -> ", paid by ${memberName(payers.keys.first())}"
            else -> ", paid by ${payers.size} people"
        }
        return "${e.title}: ${Ledger.formatMoney(e.amountMinor, currency)}$payerSummary; ${formatDate(e.date)}"
    }
    private fun AppState.settlementDescription(s: Settlement): String = "${memberName(s.fromId)} → ${memberName(s.toId)}, ${Ledger.formatMoney(s.amountMinor, Ledger.settlementCurrency(this, s))}, ${formatDate(s.date)}"
    private fun AppState.memberName(id: String): String = members.firstOrNull { it.id == id }?.name ?: id
    private fun AppState.group(id: String): Group = requireNotNull(groups.find { it.id == id }) { "Group not found." }
    private fun AppState.record(groupId: String, message: String, expenseId: String? = null, settlementId: String? = null): AppState = copy(activity = activity + Activity(id(), groupId, message, expenseId = expenseId, settlementId = settlementId))
    private fun cleanName(value: String): String = value.trim().also { require(it.isNotEmpty() && it.length <= 80 && it.none { c -> c.isISOControl() }) { "Enter a name of 1–80 characters." } }
    private fun validCurrency(value: String): String = value.also { require(it.length == 3 && it == it.uppercase() && currencyDigits(it) in 0..6) { "Choose a supported currency." } }
    // Expense and settlement dates encode a calendar date at UTC midnight.
    private fun formatDate(value: Long): String = calendarDateLabel(value)
    private fun validMoney(value: Long) { require(value in 1..MAX_MONEY) { "Enter a positive amount within the supported limit." } }
    private fun validDate(value: Long) { require(value in 0..7_258_118_400_000L) { "Choose a date between 1970 and 2200." } }
    private fun uniqueIds(values: List<String>) { require(values.all { it.isNotBlank() && it.length <= 200 } && values.distinct().size == values.size) { "Duplicate or invalid record identifiers." } }
    private fun id(): String = newId()

    /** Used by device tests and lifecycle owners that explicitly dispose of the repository. */
    fun close() = database.close()

    companion object {
        private const val ACTIVITY_LINK_VERSION = 1
        private const val MAX_MONEY = MAX_TRANSACTION_MINOR
        private const val DEMO_ID = "optional-demo-weekend"
        private val THEMES = setOf("SYSTEM", "LIGHT", "DARK")
        private val STATE_FIELDS = setOf("profileName", "members", "groups", "expenses", "settlements", "activity", "themeMode", "onboardingComplete")
    }
}
