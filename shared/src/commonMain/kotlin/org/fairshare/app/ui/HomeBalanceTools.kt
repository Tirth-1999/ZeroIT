package org.fairshare.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.fairshare.app.math.BigInteger
import org.fairshare.app.domain.Ledger
import org.fairshare.app.model.AppState
import org.fairshare.app.model.Group
import org.fairshare.app.model.SELF_ID

internal enum class BalanceFilter(val label: String) {
    ALL("All"), OWE("You owe"), OWED("Owed to you"), UNSETTLED("Unsettled"), SETTLED("Settled")
}
internal enum class BalanceSort(val label: String) {
    NEWEST("Newest groups first"), NAME("Name A–Z"), LARGEST("Largest first"), SMALLEST("Smallest first")
}
internal data class HomeBalanceItem(
    val id: String,
    val name: String,
    val balances: Map<String, Long>,
    val hasDebt: Boolean = balances.values.any { it < 0L },
    val hasReceivable: Boolean = balances.values.any { it > 0L },
    val createdAt: Long = 0L,
)

/** Use real repayment contexts for status: opposite obligations must not disappear into a net.
 * The displayed and sortable balances remain per-currency net values. */
private fun actionableHomeFlags(state: AppState, groups: List<Group>, friendId: String? = null): Pair<Boolean, Boolean> {
    var debt = false
    var receivable = false
    groups.forEach { group ->
        Ledger.groupCurrencies(state, group.id).forEach { currency ->
            Ledger.transfers(state, group.id, currency).forEach { transfer ->
                if (transfer.amountMinor > 0L) {
                    if (transfer.fromId == SELF_ID && (friendId == null || transfer.toId == friendId)) debt = true
                    if (transfer.toId == SELF_ID && (friendId == null || transfer.fromId == friendId)) receivable = true
                }
            }
        }
    }
    return debt to receivable
}

internal fun groupHomeBalanceItem(state: AppState, groupId: String, name: String): HomeBalanceItem {
    val group = state.groups.first { it.id == groupId }
    val balances = Ledger.groupCurrencies(state, groupId).associateWith { Ledger.balances(state, groupId, it)[SELF_ID] ?: 0L }
    val (debt, receivable) = actionableHomeFlags(state, listOf(group))
    return HomeBalanceItem(groupId, name, balances, debt, receivable, createdAt = group.createdAt)
}

internal fun nonGroupHomeBalanceItem(
    state: AppState,
    name: String,
    projection: Pair<AppState, Group> = nonGroupProjection(state),
): HomeBalanceItem {
    val (projected, group) = projection
    val balances = Ledger.groupCurrencies(projected, group.id).associateWith { Ledger.balances(projected, group.id, it)[SELF_ID] ?: 0L }
    val (debt, receivable) = actionableHomeFlags(state, state.groups.filter { it.isNonGroup })
    return HomeBalanceItem(NON_GROUP_ID, name, balances, debt, receivable)
}

internal fun friendHomeBalanceItem(state: AppState, memberId: String, name: String): HomeBalanceItem {
    val contexts = state.groups.filter { SELF_ID in it.memberIds && memberId in it.memberIds }
    val (debt, receivable) = actionableHomeFlags(state, contexts, memberId)
    return HomeBalanceItem(memberId, name, Ledger.friendBalances(state, memberId), debt, receivable)
}

/** Compute configured repayments once per group/currency, rather than twice for each friend.
 * Hidden and removed memberships remain financial contexts, as in friendBalances. */
internal fun friendHomeBalanceItems(state: AppState): List<HomeBalanceItem> {
    val people = state.members.filter { it.id != SELF_ID }
    val balances = people.associate { it.id to linkedMapOf<String, BigInteger>() }
    val debts = mutableSetOf<String>()
    val receivables = mutableSetOf<String>()
    state.groups.filter { SELF_ID in it.memberIds }.forEach { group ->
        val participants = group.memberIds.filter { it in balances }
        if (participants.isNotEmpty()) Ledger.groupCurrencies(state, group.id).forEach { currency ->
            participants.forEach { id -> balances.getValue(id).getOrPut(currency) { BigInteger.ZERO } }
            Ledger.transfers(state, group.id, currency).forEach { transfer ->
                val friend = when {
                    transfer.fromId == SELF_ID -> transfer.toId
                    transfer.toId == SELF_ID -> transfer.fromId
                    else -> null
                }
                val amounts = friend?.let { balances[it] }
                if (friend != null && amounts != null) {
                    val amount = BigInteger.valueOf(transfer.amountMinor)
                    amounts[currency] = amounts.getValue(currency) + if (transfer.toId == SELF_ID) amount else -amount
                    if (transfer.amountMinor > 0) {
                        if (transfer.toId == SELF_ID) receivables += friend
                        else debts += friend
                    }
                }
            }
        }
    }
    return people.map { person ->
        val amounts = balances.getValue(person.id).mapValues { (_, amount) ->
            require(amount >= BigInteger.valueOf(Long.MIN_VALUE) && amount <= BigInteger.valueOf(Long.MAX_VALUE)) { "Amount exceeds supported range" }
            amount.toLong()
        }
        HomeBalanceItem(person.id, person.name, amounts, person.id in debts, person.id in receivables)
    }
}

/** Currency balances are never added or converted to order unrelated currencies. */
internal fun homeBalanceItems(items: List<HomeBalanceItem>, filter: BalanceFilter, sort: BalanceSort, currency: String): List<HomeBalanceItem> {
    val matching = items.filter { item -> when (filter) {
        BalanceFilter.ALL -> true
        BalanceFilter.OWE -> item.hasDebt
        BalanceFilter.OWED -> item.hasReceivable
        BalanceFilter.UNSETTLED -> item.hasDebt || item.hasReceivable
        BalanceFilter.SETTLED -> !item.hasDebt && !item.hasReceivable
    } }
    if (sort == BalanceSort.NEWEST) return matching.asReversed().sortedWith(
        compareBy<HomeBalanceItem> { it.id == NON_GROUP_ID }.thenByDescending { it.createdAt }
    )
    val names = compareBy<HomeBalanceItem> { it.name.lowercase() }.thenBy { it.id }
    if (sort == BalanceSort.NAME) return matching.sortedWith(names)
    fun value(item: HomeBalanceItem): BigInteger {
        val amount = BigInteger.valueOf(item.balances[currency] ?: 0L)
        return when (filter) {
            BalanceFilter.OWE -> if (amount.signum() < 0) amount.abs() else BigInteger.ZERO
            BalanceFilter.OWED -> if (amount.signum() > 0) amount else BigInteger.ZERO
            else -> amount.abs()
        }
    }
    // Rows without a balance in the selected unit follow comparable rows in either direction.
    return matching.sortedWith(Comparator { a, b ->
        val av = value(a); val bv = value(b)
        when {
            av == BigInteger.ZERO && bv != BigInteger.ZERO -> 1
            bv == BigInteger.ZERO && av != BigInteger.ZERO -> -1
            av != bv -> if (sort == BalanceSort.LARGEST) bv.compareTo(av) else av.compareTo(bv)
            else -> names.compare(a, b)
        }
    })
}

@Composable
internal fun HomeBalanceToolsDialog(filter: BalanceFilter, sort: BalanceSort, currency: String, currencies: List<String>, onFilter: (BalanceFilter) -> Unit, onSort: (BalanceSort) -> Unit, onCurrency: (String) -> Unit, onDismiss: () -> Unit, allowNewest: Boolean = true) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Sort and filter") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Show", style = MaterialTheme.typography.labelLarge)
            BalanceFilter.entries.chunked(2).forEach { options -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { options.forEach { option ->
                FilterChip(selected = filter == option, onClick = { onFilter(option) }, label = { Text(option.label) }, modifier = Modifier.weight(1f).testTag("balance_filter_${option.name}"))
            }; if (options.size == 1) Spacer(Modifier.weight(1f)) } }
            Text("Sort by", style = MaterialTheme.typography.labelLarge)
            BalanceSort.entries.filter { allowNewest || it != BalanceSort.NEWEST }.forEach { option ->
                FilterChip(selected = sort == option, onClick = { onSort(option) }, label = { Text(option.label) }, modifier = Modifier.fillMaxWidth().testTag("balance_sort_${option.name}"))
            }
            if (sort in listOf(BalanceSort.LARGEST, BalanceSort.SMALLEST) && currencies.isNotEmpty()) {
                Text("Compare amounts in", style = MaterialTheme.typography.labelLarge)
                currencies.chunked(3).forEach { codes -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { codes.forEach { code -> FilterChip(selected = currency == code, onClick = { onCurrency(code) }, label = { Text(code) }, modifier = Modifier.testTag("balance_sort_currency_$code")) } } }
                Text("Other currencies stay separate. Rows with no matching balance appear last.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("balance_tools_done")) { Text("Done") } }, dismissButton = {
        TextButton(onClick = { onFilter(BalanceFilter.ALL); onSort(if (allowNewest) BalanceSort.NEWEST else BalanceSort.NAME) }, modifier = Modifier.testTag("balance_tools_reset")) { Text("Reset") }
    })
}

@Composable
internal fun HomeBalanceSummary(filter: BalanceFilter, sort: BalanceSort, currency: String) {
    if (filter != BalanceFilter.ALL || sort != BalanceSort.NAME) Text(
        listOfNotNull(filter.label.takeIf { filter != BalanceFilter.ALL }, if (sort != BalanceSort.NAME) if (sort == BalanceSort.NEWEST) sort.label else "${sort.label} · $currency" else null).joinToString(" · "),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("home_balance_summary")
    )
}
