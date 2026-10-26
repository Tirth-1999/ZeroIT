package org.fairshare.app.ui

import org.fairshare.app.math.BigInteger
import org.fairshare.app.model.AppState
import org.fairshare.app.model.Group

/** Financial summaries shared by tab sessions. Keep this above the session state holder:
 * query edits, navigation and restoring a tab must not recalculate its ledger. */
internal data class HomeLedgerPresentation(
    val nonGroupState: AppState,
    val nonGroup: Group,
    val groupItemsById: Map<String, HomeBalanceItem>,
    val nonGroupItem: HomeBalanceItem,
    val friendItems: List<HomeBalanceItem>,
    val overallBalances: Map<String, BigInteger>,
    val groupCurrencies: List<String>,
    val friendCurrencies: List<String>,
)

internal fun homeLedgerPresentation(state: AppState): HomeLedgerPresentation {
    val projection = nonGroupProjection(state)
    // Include hidden and direct contexts: the overall financial total has always
    // included these ledgers even though the Groups list hides their individual rows.
    val groupItems = state.groups.associate { group ->
        group.id to groupHomeBalanceItem(state, group.id, group.name)
    }
    val friends = friendHomeBalanceItems(state)
    val overall = linkedMapOf<String, BigInteger>()
    groupItems.values.forEach { item ->
        item.balances.forEach { (currency, amount) ->
            overall[currency] = (overall[currency] ?: BigInteger.ZERO) + BigInteger.valueOf(amount)
        }
    }
    return HomeLedgerPresentation(
        nonGroupState = projection.first,
        nonGroup = projection.second,
        groupItemsById = groupItems,
        nonGroupItem = nonGroupHomeBalanceItem(state, "Non-group expenses", projection),
        friendItems = friends,
        overallBalances = overall.filterValues { it.signum() != 0 },
        groupCurrencies = state.groups.filterNot { it.isHidden }
            .flatMap { groupItems.getValue(it.id).balances.keys }.distinct().sorted(),
        friendCurrencies = friends.flatMap { it.balances.keys }.distinct().sorted(),
    )
}
