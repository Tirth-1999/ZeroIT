package org.fairshare.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import org.fairshare.app.domain.Ledger
import org.fairshare.app.math.BigInteger
import org.fairshare.app.platform.moneyText
import org.fairshare.app.model.*
import org.fairshare.app.platform.calendarDateLabel
import org.fairshare.app.platform.creationDateLabel
import org.fairshare.app.platform.rememberReducedMotion

/** A dated transaction feed with focused destinations for balance tools and history. */
@Composable
internal fun GroupWorkspaceScreen(
    state: AppState, group: Group, busy: Boolean, tab: Int, onTab: (Int) -> Unit,
    scrollStates: List<ScrollState>, expenseListState: LazyListState, activityListState: LazyListState,
    onRefresh: suspend () -> Unit, onExpense: (String) -> Unit,
    onSettle: (Transfer?, String) -> Unit, onDeleteSettlement: (String) -> Unit,
    onEditExpense: (String) -> Unit = onExpense, onDeleteExpense: (String) -> Unit = {},
    onEditSettlement: ((String) -> Unit)? = null,
    onRestoreExpense: ((String) -> Unit)? = null,
) {
    var confirmSettlement by remember(group.id) { mutableStateOf<String?>(null) }
    var showOtherSettlements by rememberSaveable(group.id) { mutableStateOf(false) }
    var showRecordedPayments by rememberSaveable(group.id) { mutableStateOf(false) }
    val reducedMotion = rememberReducedMotion()
    val currencies = remember(state, group.id) { Ledger.groupCurrencies(state, group.id) }
    val balancesByCurrency = remember(state, group.id) { currencies.associateWith { Ledger.balances(state, group.id, it) } }
    val ownBalances = currencies.associateWith { balancesByCurrency.getValue(it)[SELF_ID] ?: 0L }
    val nonzero = ownBalances.filterValues { it != 0L }
    val displayedBalances = if (nonzero.isNotEmpty()) nonzero.entries.take(2).associate { it.toPair() } else ownBalances.entries.take(1).associate { it.toPair() }
    var balanceCurrency by rememberSaveable(group.id) { mutableStateOf(nonzero.keys.firstOrNull() ?: currencies.first()) }
    val selectedCurrency = balanceCurrency.takeIf { it in currencies } ?: currencies.first()
    val workspaceColor = if (group.theme == GroupTheme.CLASSIC) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.primary.copy(alpha = .05f).compositeOver(MaterialTheme.colorScheme.surface)
    Column(Modifier.fillMaxSize().background(workspaceColor)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val amountStyle = (if (displayedBalances.size > 1) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium).copy(fontWeight = FontWeight.SemiBold)
            GroupHeaderBalances(displayedBalances, amountStyle, group.activeMemberIds.size, Modifier.fillMaxWidth())
            if (nonzero.size > displayedBalances.size) TextButton(onClick = { onTab(1) }, modifier = Modifier.testTag("group_all_currencies")) { Text("View all ${nonzero.size} currencies") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = tab == 4, onClick = { onTab(4) }, enabled = !busy && group.memberIds.size > 1, label = { Text("Settle up") }, leadingIcon = { Icon(Icons.Default.Payments, null, Modifier.size(18.dp)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("group_action_settle"))
            listOf(Triple(0, "Expenses", "group_tab_expenses"), Triple(1, "Balances", "group_tab_balances"), Triple(3, "Totals", "group_tab_totals"), Triple(2, "Activity", "group_tab_activity")).forEach { (index, label, tag) ->
                FilterChip(selected = tab == index, onClick = { onTab(index) }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp).testTag(tag))
            }
        }
        // The curved top and sides define a fixed, open-bottom window around the feed.
        // Scroll content never introduces a rounded bottom cap or a stopping border.
        val panelColor = MaterialTheme.colorScheme.surfaceContainerLow
        val panelRim = MaterialTheme.colorScheme.primary.copy(alpha = if (group.theme == GroupTheme.CLASSIC) .14f else .22f)
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp, start = 8.dp, end = 8.dp)
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(panelColor).testTag("group_header_boundary")) {
            GroupRefreshContainer(onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                Crossfade(targetState = tab, animationSpec = tween(if (reducedMotion) 0 else 150), label = "group destination transition") { visibleTab ->
                    key(group.id, visibleTab) {
                        if (visibleTab == 0 || visibleTab == 2) {
                            Box(Modifier.fillMaxSize().testTag("group_content_panel")) {
                                if (visibleTab == 0) GroupExpenseFeed(state, group, expenseListState, onExpense)
                                else {
                                    val activities = remember(state.activity, group.id) { state.activity.filter { it.groupId == group.id }.asReversed().sortedByDescending { it.at } }
                                    LazyColumn(state = activityListState, modifier = Modifier.fillMaxSize().testTag("group_expense_list"), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 28.dp, bottom = 128.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        item(key = "activity-header") { Text("Group activity", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("group_activity_title")) }
                                        if (activities.isEmpty()) item(key = "activity-empty") { GroupNote("Your group's changes will appear here.") }
                                        items(activities, key = { "activity-${it.id}" }) { activity -> ActivityEntryCard(state, activity, busy, onEditExpense, onDeleteExpense, onEditSettlement = onEditSettlement, onDeleteSettlement = onDeleteSettlement, onRestoreExpense = onRestoreExpense) }
                                    }
                                }
                            }
                        } else {
                        Column(Modifier.fillMaxSize().verticalScroll(scrollStates[when (visibleTab) { 1 -> 0; 3 -> 1; else -> 2 }]).testTag("group_expense_list")) {
                            Column(Modifier.fillMaxWidth().testTag("group_content_panel").padding(horizontal = 18.dp).padding(top = 28.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (visibleTab != 0 && visibleTab != 3) {
                                        Text(when (visibleTab) { 1 -> "Everyone’s balance"; 4 -> "Settle up"; else -> "Group activity" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag(if (visibleTab == 4) "group_settlement_title" else if (visibleTab == 1) "group_balances_title" else "group_activity_title"))
                                    }
                                    when (visibleTab) {
                                        1 -> {
                                            GroupCurrencyChoices(currencies, selectedCurrency) { balanceCurrency = it }
                                            val balances = balancesByCurrency.getValue(selectedCurrency)
                                            GroupSurface(Modifier.testTag("group_everyone_balances")) {
                                                group.memberIds.forEach { id ->
                                                    val balance = balances[id] ?: 0L
                                                    GroupMemberBalance(state, id, balance, selectedCurrency)
                                                }
                                            }
                                            if (group.id == NON_GROUP_ID) GroupNote("Balances combine separate non-group expenses. Settle up lets you choose the original people and expense context.")
                                            else GroupNote("Each person’s balance includes expenses and recorded payments. Currencies stay separate.")
                                        }
                                        4 -> {
                                            GroupNote("Your payments, one currency at a time.")
                                            GroupCurrencyChoices(currencies, selectedCurrency) { balanceCurrency = it }
                                            val transfers = remember(state, group.id, selectedCurrency) { if (group.id == NON_GROUP_ID) emptyList() else Ledger.transfers(state, group.id, selectedCurrency) }
                                            val outgoing = transfers.filter { it.fromId == SELF_ID }
                                            val incoming = transfers.filter { it.toId == SELF_ID }
                                            val other = transfers.filter { it.fromId != SELF_ID && it.toId != SELF_ID }
                                            if (group.id == NON_GROUP_ID) {
                                                GroupNote("Choose the people and original expense context. Debts from separate non-group expenses are not combined into payment suggestions.")
                                            } else {
                                                if (outgoing.isNotEmpty()) {
                                                    GroupSectionLabel("You owe")
                                                    outgoing.forEach { transfer -> GroupSuggestedPayment(state, transfer, selectedCurrency, busy, onSettle) }
                                                }
                                                if (incoming.isNotEmpty()) {
                                                    GroupSectionLabel("Owed to you")
                                                    incoming.forEach { transfer -> GroupSuggestedPayment(state, transfer, selectedCurrency, busy, onSettle) }
                                                }
                                                if (outgoing.isEmpty() && incoming.isEmpty()) GroupSurface(color = settlementTileColor()) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                        Icon(Icons.Default.CheckCircleOutline, null, tint = groupBalanceColor(1L))
                                                        Text("You’re settled up", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                                                    }
                                                    GroupNote("No suggested payments for you in $selectedCurrency.")
                                                }
                                            }
                                            if (group.memberIds.size > 1) OutlinedButton(onClick = { onSettle(null, selectedCurrency) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("record_payment")) { Text(if (group.id == NON_GROUP_ID) "Choose people and record payment" else "Record a different payment") }
                                            GroupNote("Record a payment made elsewhere. ZeroIT does not move money.")
                                            if (other.isNotEmpty()) {
                                                TextButton(onClick = { showOtherSettlements = !showOtherSettlements }, modifier = Modifier.fillMaxWidth().testTag("group_other_settlements")) {
                                                    Text("Other members’ payments · ${other.size}", Modifier.weight(1f))
                                                    Icon(if (showOtherSettlements) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                                                }
                                                if (showOtherSettlements) other.forEach { transfer -> GroupSuggestedPayment(state, transfer, selectedCurrency, busy, onSettle) }
                                            }
                                            val payments = state.settlements.filter { it.groupId == group.id && !it.deleted && Ledger.settlementCurrency(state, it) == selectedCurrency }.sortedByDescending { it.createdAt }
                                            if (payments.isNotEmpty()) {
                                                TextButton(onClick = { showRecordedPayments = !showRecordedPayments }, modifier = Modifier.fillMaxWidth().testTag("group_recorded_payments")) {
                                                    Text("Recorded payments · ${payments.size}", Modifier.weight(1f))
                                                    Icon(if (showRecordedPayments) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                                                }
                                                if (showRecordedPayments) payments.forEach { payment ->
                                                    GroupSurface(color = settlementTileColor()) {
                                                        Text("${groupPerson(state, payment.fromId)} paid ${groupPerson(state, payment.toId)}")
                                                        Text(moneyText(BigInteger.valueOf(payment.amountMinor), selectedCurrency), fontWeight = FontWeight.SemiBold)
                                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                            GroupNote(calendarDateLabel(payment.date))
                                                            TextButton(onClick = { confirmSettlement = payment.id }, enabled = !busy, modifier = Modifier.testTag("delete_settlement_${payment.id}")) { Text("Delete record", color = MaterialTheme.colorScheme.error) }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        3 -> GroupTotals(state, group, currencies)
                                    }
                            }
                            // Keep the final content clear of the floating expense action.
                            Spacer(Modifier.height(100.dp))
                        }
                        }
                    }
                }
            }
            // A quiet cap masks the first 12 dp of moving text beneath the curved edge.
            // It has no pointer handler, so refresh and scroll gestures reach the viewport.
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(12.dp).background(panelColor))
            Canvas(Modifier.matchParentSize()) {
                val radius = 26.dp.toPx().coerceAtMost(size.width / 2f)
                val inset = .5.dp.toPx()
                val curve = Path().apply {
                    moveTo(inset, size.height)
                    lineTo(inset, radius)
                    cubicTo(inset, radius * .448f, radius * .448f, inset, radius, inset)
                    lineTo(size.width - radius, inset)
                    cubicTo(size.width - radius * .448f, inset, size.width - inset, radius * .448f, size.width - inset, radius)
                    lineTo(size.width - inset, size.height)
                }
                drawPath(curve, panelRim, style = Stroke(1.dp.toPx()))
            }
        }
    }
    if (confirmSettlement != null) AlertDialog(onDismissRequest = { confirmSettlement = null }, title = { Text("Delete this payment record?") }, text = { Text("Balances will be recalculated. The deletion stays in group history.") }, confirmButton = { TextButton(onClick = { val id = confirmSettlement; confirmSettlement = null; if (id != null) onDeleteSettlement(id) }, modifier = Modifier.testTag("confirm_delete_settlement")) { Text("Delete record") } }, dismissButton = { TextButton(onClick = { confirmSettlement = null }) { Text("Cancel") } })
}

/** Both currencies retain equal visual priority; every amount is fully rendered. */
@Composable
private fun GroupHeaderBalances(balances: Map<String, Long>, amountStyle: TextStyle, memberCount: Int, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        // Only use genuinely spare space; money keeps its original size and priority.
        val naturalAmountWidth = balances.entries.maxOfOrNull { (currency, own) ->
            measurer.measure(AnnotatedString(moneyText(BigInteger.valueOf(own).abs(), currency)), amountStyle, softWrap = false).size.width
        } ?: 0
        val artworkWidth = with(density) { 120.dp.toPx() }
        val showPeople = balances.size == 1 && memberCount > 0 &&
            constraints.maxWidth - naturalAmountWidth > artworkWidth && density.fontScale <= 1.3f
        val reservedWidth = if (showPeople) artworkWidth else 0f
        // At accessibility text sizes, complete amounts need the full available line.
        // Normal-size multi-currency balances remain equally weighted peers.
        val stackAmounts = balances.size > 1 && density.fontScale > 1.3f
        val cellWidth = if (stackAmounts) constraints.maxWidth.toFloat() else (constraints.maxWidth - reservedWidth - with(density) { (if (balances.size > 1) 13.dp else 0.dp).toPx() }) / balances.size
        val amounts = balances.map { (currency, own) -> moneyText(BigInteger.valueOf(own).abs(), currency) }
        // Measure the final style at the actual cell width. Font hinting and fractional
        // layout rounding mean scaling a single unbounded measurement is insufficient.
        val textConstraints = Constraints(maxWidth = (cellWidth.toInt() - 2).coerceAtLeast(1))
        var fittedSize = amountStyle.fontSize.value
        while (fittedSize > 20f) {
            val candidate = amountStyle.copy(fontSize = fittedSize.sp)
            val allFit = amounts.all { text ->
                val layout = measurer.measure(AnnotatedString(text), candidate, softWrap = false, maxLines = 1, constraints = textConstraints)
                !layout.hasVisualOverflow
            }
            if (allFit) break
            fittedSize = (fittedSize - .25f).coerceAtLeast(20f)
        }
        // System font scaling still applies. Exceptionally large values retain a
        // readable floor and may wrap, never ellipsize or hide digits behind controls.
        val fittedStyle = amountStyle.copy(fontSize = fittedSize.sp)
        @Composable fun BalanceCell(index: Int, currency: String, own: Long, cellModifier: Modifier) {
            val direction = if (own == 0L) "All settled up" else if (own > 0L) "You get back" else "You owe"
            Column(cellModifier.testTag("group_balance_$currency"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(direction, style = MaterialTheme.typography.bodyMedium, color = groupBalanceColor(own))
                Text(moneyText(BigInteger.valueOf(own).abs(), currency), style = fittedStyle, softWrap = true, color = groupBalanceColor(own),
                    modifier = Modifier.testTag(if (index == 0) "group_balance" else "group_balance_amount_$currency")
                        .semantics { contentDescription = "$direction ${moneyText(BigInteger.valueOf(own).abs(), currency)} $currency" })
            }
        }
        if (stackAmounts) {
            Column(Modifier.fillMaxWidth().testTag("group_balance_row"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                balances.entries.forEachIndexed { index, (currency, own) ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    BalanceCell(index, currency, own, Modifier.fillMaxWidth())
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().testTag("group_balance_row"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                balances.entries.forEachIndexed { index, (currency, own) ->
                    if (index > 0) Box(Modifier.width(1.dp).height(48.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    BalanceCell(index, currency, own, Modifier.weight(1f))
                }
                if (showPeople) GroupPeopleIllustration(memberCount, Modifier.padding(start = 10.dp))
            }
        }
    }
}

/** Shared currency context without duplicating a single-currency label under the header. */
@Composable
private fun GroupCurrencyChoices(currencies: List<String>, selectedCurrency: String, onSelect: (String) -> Unit) {
    if (currencies.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        currencies.forEach { currency -> FilterChip(selected = currency == selectedCurrency, onClick = { onSelect(currency) }, label = { Text(currency) }, modifier = Modifier.testTag("group_currency_$currency")) }
    }
}

@Composable
private fun GroupSuggestedPayment(state: AppState, transfer: Transfer, currency: String, busy: Boolean, onSettle: (Transfer?, String) -> Unit) {
    GroupSurface(Modifier.testTag("group_settlement_${transfer.fromId}_${transfer.toId}")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val label = when {
                    transfer.fromId == SELF_ID -> "You pay ${groupPerson(state, transfer.toId)}"
                    transfer.toId == SELF_ID -> "${groupPerson(state, transfer.fromId)} pays you"
                    else -> "${groupPerson(state, transfer.fromId)} pays ${groupPerson(state, transfer.toId)}"
                }
                Text(label, fontWeight = FontWeight.Medium)
                Text(moneyText(BigInteger.valueOf(transfer.amountMinor), currency), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = if (transfer.fromId == SELF_ID) groupBalanceColor(-1L) else if (transfer.toId == SELF_ID) groupBalanceColor(1L) else MaterialTheme.colorScheme.onSurface)
            }
            TextButton(onClick = { onSettle(transfer, currency) }, enabled = !busy, modifier = Modifier.testTag("settle_up")) { Text("Record") }
        }
    }
}

private data class GroupFeedEntry(val addedAt: Long, val expense: Expense? = null, val payment: Settlement? = null)

@Composable
private fun GroupExpenseFeed(state: AppState, group: Group, listState: LazyListState, onExpense: (String) -> Unit) {
    val entries = remember(state.expenses, state.settlements, group.id) {
        (state.expenses.filter { it.groupId == group.id && !it.deleted }.map { GroupFeedEntry(it.createdAt, expense = it) } + state.settlements.filter { it.groupId == group.id && !it.deleted }.map { GroupFeedEntry(it.createdAt, payment = it) }).asReversed().sortedByDescending { it.addedAt }
    }
    val datedEntries = remember(entries) { entries.groupBy { creationDateLabel(it.addedAt, it.expense?.date ?: requireNotNull(it.payment).date) } }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("group_expense_list"), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 28.dp, bottom = 128.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (entries.isEmpty()) item(key = "expense-empty") {
        Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.ReceiptLong, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Your expenses start here", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            GroupNote(if (group.memberIds.size == 1 && !group.isNonGroup) "Use the people bubble above to add members, then add your first expense." else "Add your first expense below. Everyone's share will be calculated for you.")
        }
    }

        datedEntries.forEach { (date, transactions) ->
            item(key = "date-$date") { GroupSectionLabel(date) }
            items(transactions, key = { it.expense?.let { expense -> "expense-${expense.id}" } ?: "payment-${requireNotNull(it.payment).id}" }) { entry -> GroupFeedCard(state, entry, onExpense) }
        }
    }
}

@Composable
private fun GroupFeedCard(state: AppState, entry: GroupFeedEntry, onExpense: (String) -> Unit) {
    val cardColor = if (MaterialTheme.colorScheme.surface.luminance() < .5f)
        MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer
    val expense = entry.expense
    if (expense != null) {
        val currency = Ledger.expenseCurrency(state, expense)
        val payers = Ledger.expensePayers(expense).filterValues { it > 0L }
        Surface(onClick = { onExpense(expense.id) }, shape = RoundedCornerShape(16.dp), color = cardColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().testTag("group_expense_${expense.id}")) {
            GroupTransactionContent(expense.title,
                if (expense.deleted) "Deleted · tap to restore" else if (payers.size == 1) "${groupPerson(state, payers.keys.first())} paid" else "${payers.size} people paid",
                calendarDateLabel(expense.date), Ledger.formatMoney(expense.amountMinor, currency), currency,
                if (expense.deleted) Icons.Default.DeleteOutline else Icons.Default.ReceiptLong,
                "group_expense_title_${expense.id}", "group_expense_amount_${expense.id}")
        }
    } else entry.payment?.let { payment ->
        val currency = Ledger.settlementCurrency(state, payment)
        Surface(shape = RoundedCornerShape(16.dp), color = settlementTileColor(cardColor), contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().testTag("group_payment_${payment.id}")) {
            GroupPaymentContent("${groupPerson(state, payment.fromId)} paid ${groupPerson(state, payment.toId)}", calendarDateLabel(payment.date),
                Ledger.formatMoney(payment.amountMinor, currency), currency,
                "group_payment_title_${payment.id}", "group_payment_amount_${payment.id}")
        }
    }
}

/** Recorded payments are secondary ledger events, so keep their row quieter and shorter than expenses. */
@Composable
private fun GroupPaymentContent(title: String, date: String, amount: String, currency: String, titleTag: String, amountTag: String) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val amountStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        val amountWidth = measurer.measure(AnnotatedString(amount), amountStyle, softWrap = false).size.width
        val stack = density.fontScale > 1.3f || maxWidth < 280.dp ||
            amountWidth + with(density) { 150.dp.toPx() } > constraints.maxWidth
        @Composable fun Details(modifier: Modifier) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(titleTag))
                GroupNote(date)
            }
        }
        @Composable fun Amount(modifier: Modifier) {
            Column(modifier, horizontalAlignment = Alignment.End) {
                Text(amount, style = amountStyle, maxLines = 1, modifier = Modifier.testTag(amountTag))
                GroupNote(currency)
            }
        }
        if (stack) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CheckCircleOutline, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Details(Modifier.weight(1f))
            }
            Amount(Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.CheckCircleOutline, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Details(Modifier.weight(1f))
            Amount(Modifier)
        }
    }
}

/** Stack the amount when a trailing column would squeeze the transaction into single glyphs. */
@Composable
private fun GroupTransactionContent(title: String, subtitle: String, date: String, amount: String, currency: String, icon: androidx.compose.ui.graphics.vector.ImageVector, titleTag: String, amountTag: String) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val amountStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp)) {
        val amountWidth = measurer.measure(AnnotatedString(amount), amountStyle, softWrap = false).size.width
        val stack = density.fontScale > 1.3f || maxWidth < 280.dp || amountWidth + with(density) { 158.dp.toPx() } > constraints.maxWidth
        @Composable fun Details(modifier: Modifier) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(titleTag))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                GroupNote(date)
            }
        }
        @Composable fun Amount(modifier: Modifier) {
            Column(modifier, horizontalAlignment = Alignment.End) {
                Text(amount, style = amountStyle, modifier = Modifier.testTag(amountTag))
                GroupNote(currency)
            }
        }
        if (stack) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) { FeedIcon(icon); Details(Modifier.weight(1f)) }
            Amount(Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FeedIcon(icon); Details(Modifier.weight(1f)); Amount(Modifier)
        }
    }
}

@Composable
private fun GroupMemberBalance(state: AppState, id: String, balance: Long, currency: String) {
    val value = moneyText(BigInteger.valueOf(balance).abs(), currency)
    val style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("group_member_balance_$id")) {
        val width = measurer.measure(AnnotatedString(value), style, softWrap = false).size.width
        val stack = density.fontScale > 1.3f || maxWidth < 280.dp || width > constraints.maxWidth * .45f
        @Composable fun Person(modifier: Modifier) {
            Column(modifier) {
                Text(groupPerson(state, id), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                GroupNote(if (balance > 0L) "Gets back" else if (balance < 0L) "Owes" else "Settled up")
            }
        }
        if (stack) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { GroupAvatar(groupPerson(state, id)); Person(Modifier.weight(1f)) }
            Text(value, style = style, color = groupBalanceColor(balance), modifier = Modifier.fillMaxWidth().testTag("group_member_balance_amount_$id"))
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GroupAvatar(groupPerson(state, id)); Person(Modifier.weight(1f)); Text(value, style = style, color = groupBalanceColor(balance), modifier = Modifier.testTag("group_member_balance_amount_$id"))
        }
    }
}

@Composable
private fun FeedIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary) }
}

@Composable
private fun GroupTotals(state: AppState, group: Group, currencies: List<String>) {
    currencies.forEach { currency ->
        val expenses = state.expenses.filter { it.groupId == group.id && !it.deleted && Ledger.expenseCurrency(state, it) == currency }
        val payments = state.settlements.filter { it.groupId == group.id && !it.deleted && Ledger.settlementCurrency(state, it) == currency }
        val total = expenses.fold(BigInteger.ZERO) { sum, expense -> sum + BigInteger.valueOf(expense.amountMinor) }
        val paymentTotal = payments.fold(BigInteger.ZERO) { sum, payment -> sum + BigInteger.valueOf(payment.amountMinor) }
        // Single-currency groups already identify their currency beside the balance above.
        // Multiple currencies need explicit section labels; never imply conversion or a combined total.
        if (currencies.size > 1) Text(currency, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("group_totals_currency_$currency"))
        GroupSurface {
            TotalsRow("Spending · ${expenses.size} expenses", total, currency, "group_total_$currency")
            TotalsRow("Payments · ${payments.size}", paymentTotal, currency)
        }
        GroupSpendingChart(state, group.id, currency)
    }
}

@Composable
private fun TotalsRow(label: String, amount: BigInteger, currency: String, tag: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(moneyText(amount, currency), fontWeight = FontWeight.SemiBold, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
    }
}

@Composable
internal fun GroupMembersScreen(state: AppState, group: Group, busy: Boolean, onAddMember: () -> Unit, onRemoveMember: (String) -> Unit) {
    var removal by remember(group.id) { mutableStateOf<String?>(null) }
    val currencies = remember(state, group.id) { Ledger.groupCurrencies(state, group.id) }
    val balancesByCurrency = remember(state, group.id) { currencies.associateWith { Ledger.balances(state, group.id, it) } }
    // Preserve bilateral-debt restrictions even when a person's net balance is zero.
    // Resolve each currency once, rather than replaying the whole ledger per member.
    val unsettledMembers = remember(state, group.id) {
        currencies.flatMap { Ledger.transfers(state, group.id, it) }.flatMap { listOf(it.fromId, it.toId) }.toSet()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("${group.activeMemberIds.size} ${if (group.activeMemberIds.size == 1) "member" else "members"} · ${group.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("members_title"))
        GroupSurface {
            group.activeMemberIds.forEach { id ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("member_row_$id"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    GroupAvatar(groupPerson(state, id))
                    Column(Modifier.weight(1f)) {
                        Text(groupPerson(state, id), fontWeight = FontWeight.Medium)
                        GroupNote(if (id == SELF_ID) "You" else "Local member")
                        currencies.forEach { currency ->
                            val balance = balancesByCurrency.getValue(currency)[id] ?: 0L
                            Text("$currency · ${if (balance > 0) "gets back" else if (balance < 0) "owes" else "settled"} ${Ledger.formatMoney(balance, currency).removePrefix("-")}", style = MaterialTheme.typography.bodySmall, color = groupBalanceColor(balance), modifier = Modifier.testTag("member_balance_${id}_$currency"))
                        }
                    }
                    if (!group.isNonGroup && id != SELF_ID) {
                        val hasDebt = id in unsettledMembers
                        Column(horizontalAlignment = Alignment.End) {
                            TextButton(onClick = { removal = id }, enabled = !busy && !hasDebt, modifier = Modifier.testTag("remove_member_$id")) { Text("Remove") }
                            if (hasDebt) Text("Settle first", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        if (group.removedMemberIds.isNotEmpty()) GroupNote("${group.removedMemberIds.size} former members remain in transaction history. Select them in Add group members to bring them back.")
        if (!group.isNonGroup) FilledTonalButton(onClick = onAddMember, enabled = !busy, colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("add_members")) {
            Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(8.dp)); Text("Add group members")
        }
        GroupNote(if (group.isNonGroup) "These people are included in non-group expenses. Add or choose people when creating an expense." else "Members are stored on this device. Adding a person does not send an invitation.")
    }
    removal?.let { id ->
        AlertDialog(onDismissRequest = { if (!busy) removal = null }, title = { Text("Remove ${groupPerson(state, id)}?") }, text = { Text("Their past expenses and payments stay in the history. They will no longer be selected for new expenses. No message is sent.") }, confirmButton = { TextButton(onClick = { removal = null; onRemoveMember(id) }, enabled = !busy, modifier = Modifier.testTag("member_remove_confirm")) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removal = null }, enabled = !busy) { Text("Cancel") } })
    }
}

private fun groupPerson(state: AppState, id: String) = state.members.firstOrNull { it.id == id }?.name ?: "Unknown member"
@Composable private fun GroupNote(text: String) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun GroupSectionLabel(text: String) { Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun GroupAvatar(name: String) {
    Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Medium)
    }
}
@Composable private fun GroupSurface(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceContainerLow, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = color, contentColor = MaterialTheme.colorScheme.onSurface, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
