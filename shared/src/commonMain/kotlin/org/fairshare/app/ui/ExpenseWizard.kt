package org.fairshare.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.fairshare.app.domain.Ledger
import org.fairshare.app.math.BigDecimal
import org.fairshare.app.math.BigInteger
import org.fairshare.app.model.*
import org.fairshare.app.platform.*

/** A group's reference currency seeds new drafts; transaction overrides and legacy edits retain their units. */
internal fun initialExpenseCurrency(group: Group, existing: Expense?): String = existing?.currency ?: group.currency

/** Fixed template amounts must never be relabeled as another currency. Relative values are currency independent. */
internal fun initialExpenseSplitValues(group: Group): Map<String, String> {
    val defaults = group.expenseDefaults ?: return emptyMap()
    val fixed = defaults.splitMode == SplitMode.EXACT || defaults.splitMode == SplitMode.ADJUSTMENT
    return if (fixed && defaults.currency != null && defaults.currency != group.currency) emptyMap() else defaults.values
}

internal fun expenseCurrencySymbol(code: String): String = when (code) {
    "USD" -> "$"; "EUR" -> "€"; "GBP" -> "£"; "JPY", "CNY" -> "¥"; "INR" -> "₹"
    "CAD" -> "CA$"; "AUD" -> "A$"; "NZD" -> "NZ$"; "HKD" -> "HK$"; "SGD" -> "S$"
    "KRW" -> "₩"; "THB" -> "฿"; "PHP" -> "₱"; "VND" -> "₫"; "TRY" -> "₺"; "RUB" -> "₽"
    else -> "¤"
}

/** A draft remains local until the review step submits one validated expense. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExpenseWizard(
    state: AppState,
    group: Group,
    existing: Expense?,
    busy: Boolean,
    step: Int,
    onStep: (Int) -> Unit,
    onDirty: (Boolean) -> Unit,
    save: (Expense) -> Unit,
) {
    val key = existing?.id ?: "new-${group.id}"
    val draftId = rememberSaveable(key) { existing?.id ?: newId() }
    val originalCurrency = initialExpenseCurrency(group, existing)
    fun plain(minor: Long, code: String) = BigDecimal.valueOf(minor, currencyDigits(code)).toPlainString()
    val defaults = if (existing == null) group.expenseDefaults else null
    val eligibleMembers = if (existing == null) group.activeMemberIds else group.memberIds.filter { it in group.activeMemberIds || it in existing.owed || it in Ledger.expensePayers(existing) }
    val defaultParticipants = defaults?.participantIds?.filter { it in eligibleMembers }?.ifEmpty { eligibleMembers } ?: eligibleMembers
    val originalPayers = existing?.let { it.paidBy.ifEmpty { mapOf(it.payerId to it.amountMinor) } }
        ?: mapOf((defaults?.payerId?.takeIf { it in eligibleMembers } ?: eligibleMembers.firstOrNull { it == SELF_ID } ?: eligibleMembers.first()) to 0L)
    var title by rememberSaveable(key) { mutableStateOf(existing?.title.orEmpty()) }
    var currency by rememberSaveable(key) { mutableStateOf(originalCurrency) }
    var amount by rememberSaveable(key) { mutableStateOf(existing?.let { plain(it.amountMinor, originalCurrency) }.orEmpty()) }
    var payerIds by rememberSaveable(key) { mutableStateOf(originalPayers.keys.toList()) }
    var payerAmounts by rememberSaveable(key) { mutableStateOf(originalPayers.mapValues { if (existing == null) "" else plain(it.value, originalCurrency) }) }
    var participantIds by rememberSaveable(key) { mutableStateOf(existing?.owed?.keys?.toList() ?: if (group.id == "__non_group__" && group.memberIds.size > 2) listOf(SELF_ID) else defaultParticipants) }
    var modeName by rememberSaveable(key) { mutableStateOf(if (existing == null) (defaults?.splitMode ?: SplitMode.EQUAL).name else SplitMode.EXACT.name) }
    var values by rememberSaveable(key) { mutableStateOf(existing?.owed?.mapValues { plain(it.value, originalCurrency) } ?: initialExpenseSplitValues(group)) }
    var date by rememberSaveable(key) { mutableStateOf(existing?.let { calendarDateInput(it.date) } ?: todayDate()) }
    var notes by rememberSaveable(key) { mutableStateOf(existing?.notes.orEmpty()) }
    var error by rememberSaveable(key) { mutableStateOf("") }
    var editingDate by rememberSaveable(key) { mutableStateOf(false) }
    var currencyPicker by rememberSaveable(key) { mutableStateOf(false) }
    var currencyQuery by rememberSaveable(key) { mutableStateOf("") }
    var allCurrencies by rememberSaveable(key) { mutableStateOf(false) }
    val localeCurrency = remember { preferredLocaleCurrency() }
    val suggestedCurrencies = remember(state.expenses, state.groups, group.currency, localeCurrency) {
        val recent = state.expenses.filterNot { it.deleted }.sortedByDescending { it.date }.mapNotNull { expense ->
            expense.currency ?: state.groups.firstOrNull { it.id == expense.groupId }?.currency
        }
        (listOfNotNull(localeCurrency) + group.currency + recent + listOf("EUR", "GBP", "INR", "JPY", "CAD", "AUD", "USD")).distinct().filter { it in supportedCurrencyCodes() }.take(7)
    }
    val initialDraft = rememberSaveable(key) { listOf(title, currency, amount, payerIds.toString(), payerAmounts.toString(), participantIds.toString(), modeName, values.toString(), date, notes).toString() }
    val currentDraft = listOf(title, currency, amount, payerIds.toString(), payerAmounts.toString(), participantIds.toString(), modeName, values.toString(), date, notes).toString()
    LaunchedEffect(currentDraft) { onDirty(currentDraft != initialDraft) }
    val mode = SplitMode.valueOf(modeName)
    val reducedMotion = rememberReducedMotion()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun name(id: String) = state.members.firstOrNull { it.id == id }?.name ?: "Unknown member"
    fun total(): Long {
        require(currency.isNotBlank()) { "Choose a currency for this expense." }
        currencyDigits(currency)
        require(title.trim().isNotEmpty()) { "Add a description." }
        require(title.trim().length <= 160) { "Use a description of at most 160 characters." }
        return Ledger.parseMoney(amount, currency).also {
            require(it > 0L) { "Amount must be greater than zero." }
            require(it <= MAX_TRANSACTION_MINOR) { "Enter ${Ledger.formatMoney(MAX_TRANSACTION_MINOR, currency)} or less." }
        }
    }
    fun contributions(total: Long): Map<String, Long> {
        require(payerIds.isNotEmpty() && payerIds.all { it in group.memberIds }) { "Select who paid." }
        if (payerIds.size == 1) return mapOf(payerIds.single() to total)
        val result = payerIds.associateWith { Ledger.parseMoney(payerAmounts[it].orEmpty(), currency) }
        require(result.values.all { it > 0 }) { "Enter a positive amount for each payer." }
        // Reuse exact split validation so sums are checked with overflow-safe arithmetic.
        Ledger.split(total, payerIds, SplitMode.EXACT, payerIds.associateWith { payerAmounts[it].orEmpty() }, currency)
        return result
    }
    fun shares(total: Long): Map<String, Long> {
        require(participantIds.isNotEmpty() && participantIds.all { it in group.memberIds }) { "Select at least one person to split between." }
        return Ledger.split(total, participantIds, mode, values.filterKeys { it in participantIds }, currency)
    }
    fun expenseDate(): Long = parseCalendarDate(date).also { require(it in 0..7_258_118_400_000L) { "Choose a date between 1970 and 2200." } }
    fun validateDetails() {
        contributions(total())
    }
    fun advance() {
        runCatching { validateDetails(); if (step >= 1) shares(total()); expenseDate() }
            .onSuccess { error = ""; focus.clearFocus(); keyboard?.hide(); onStep((step + 1).coerceAtMost(2)) }
            .onFailure { error = it.message ?: "Check the expense details." }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Step ${step + 1} of 3 · ${listOf("Expense details", "Split the cost", "Review and add")[step.coerceIn(0, 2)]}", Modifier.testTag("expense_step").semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth())
        }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val duration = if (reducedMotion) 0 else 180
                val direction = if (targetState > initialState) 1 else -1
                (fadeIn(tween(duration)) + slideInHorizontally(tween(duration)) { it / 12 * direction }) togetherWith
                    (fadeOut(tween(duration)) + slideOutHorizontally(tween(duration)) { -it / 12 * direction })
            }, modifier = Modifier.weight(1f), label = "Expense step",
        ) { page ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp).testTag("expense_step_content"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (page) {
                    0 -> {
                        WizardField(title, { title = it; error = "" }, "Description", "expense_title", !busy)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                            OutlinedButton(
                                onClick = { currencyQuery = ""; allCurrencies = false; currencyPicker = true },
                                enabled = !busy,
                                shape = MaterialTheme.shapes.extraSmall,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier.weight(.22f).heightIn(min = 56.dp).testTag("expense_currency")
                                    .semantics { contentDescription = "Currency $currency. Change currency" },
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(expenseCurrencySymbol(currency), style = MaterialTheme.typography.titleLarge)
                                    Text(currency, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            WizardField(amount, { amount = it; error = "" }, "Amount", "expense_amount", !busy, numeric = true, modifier = Modifier.weight(.78f))
                        }
                        if (defaults != null) Text("Personal defaults applied · change any choice", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("expense_defaults_hint"))
                        if (defaults?.currency != null) Text(
                            if (defaults.currency != originalCurrency) "Saved ${defaults.currency} amounts cleared for $originalCurrency. Enter new amounts."
                            else "Saved amounts use ${defaults.currency}; another currency clears them.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (currency.isBlank()) Text("Choose this transaction's currency.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        WizardSection("Paid by") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Person", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (payerIds.size > 1) Text("Amount", Modifier.weight(1.3f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Paid", Modifier.width(56.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
                            }
                            eligibleMembers.forEach { id ->
                                val remaining = if (id in payerIds && payerIds.size > 1) payerRemainder(amount, currency, payerIds, payerAmounts, id) else null
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(name(id), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                    if (id in payerIds && payerIds.size > 1) WizardField(payerAmounts[id].orEmpty(), { payerAmounts = payerAmounts + (id to it); error = "" }, "Paid", "payer_amount_$id", !busy, numeric = true, modifier = Modifier.weight(1.3f), placeholder = remaining?.let { "$it left" })
                                    else if (payerIds.size > 1) Spacer(Modifier.weight(1.3f))
                                    WizardToggle(id in payerIds, "payer_$id", "${name(id)} paid", !busy) { checked -> payerIds = if (checked) payerIds + id else payerIds - id; error = "" }
                                }
                                if (id in payerIds && payerAmounts[id].isNullOrBlank() && remaining != null) TextButton(onClick = { if (payerAmounts[id].isNullOrBlank()) payerAmounts = payerAmounts + (id to remaining); error = "" }, enabled = !busy, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("payer_remaining_$id")) { Text("$remaining left · Use remaining") }
                            }
                            if (payerIds.size > 1) {
                                val paidTotal = runCatching { payerIds.fold(BigInteger.ZERO) { sum, id -> sum + BigInteger.valueOf(Ledger.parseMoney(payerAmounts[id].orEmpty().ifBlank { "0" }, currency)) } }.getOrNull()
                                val targetTotal = runCatching { Ledger.parseMoney(amount, currency) }.getOrNull()
                                if (paidTotal != null && targetTotal != null && currency.isNotBlank()) Text(
                                    if (paidTotal == BigInteger.valueOf(targetTotal)) "Contributions match the total"
                                    else "Paid ${moneyText(paidTotal, currency)} · total ${Ledger.formatMoney(targetTotal, currency)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("payer_total"))
                            }
                        }

                    }
                    1 -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(runCatching { Ledger.formatMoney(total(), currency) }.getOrDefault(amount), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                            Text("${participantIds.size} people", style = MaterialTheme.typography.bodyMedium)
                        }
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                SplitMode.entries.forEach { option ->
                                    val selected = mode == option
                                    Surface(shape = MaterialTheme.shapes.medium, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.weight(1f)) {
                                        Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("split_mode_${option.name.lowercase()}").semantics { contentDescription = splitLabel(option) }.selectable(selected, enabled = !busy, role = Role.RadioButton) { modeName = option.name; values = emptyMap(); error = "" }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                            Text(when (option) { SplitMode.EQUAL -> "="; SplitMode.EXACT -> "0.00"; SplitMode.PERCENT -> "%"; SplitMode.SHARES -> "1:2"; SplitMode.ADJUSTMENT -> "+/−" }, style = MaterialTheme.typography.titleMedium)
                                            Text(when (option) { SplitMode.EQUAL -> "Equal"; SplitMode.EXACT -> "Exact"; SplitMode.PERCENT -> "Percent"; SplitMode.SHARES -> "Shares"; SplitMode.ADJUSTMENT -> "Adjust" }, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                        Text(when (mode) {
                            SplitMode.EQUAL -> "Choose who shares this expense equally."
                            SplitMode.EXACT -> "Enter each person's amount."
                            SplitMode.PERCENT -> "Enter each person's percentage · total 100%."
                            SplitMode.SHARES -> "Use relative shares · 2 shares is twice 1."
                            SplitMode.ADJUSTMENT -> "Split equally, then add or subtract adjustments."
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val preview = runCatching { shares(total()) }
                        WizardSection(if (mode == SplitMode.EQUAL) "Each person's share" else splitLabel(mode)) {
                            eligibleMembers.forEach { id ->
                                val selected = id in participantIds
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    WizardToggle(selected, "participant_$id", "Split with ${name(id)}", !busy) { checked -> participantIds = if (checked) participantIds + id else participantIds - id; error = "" }
                                    Column(Modifier.weight(1f)) {
                                        Text(name(id))
                                        if (mode != SplitMode.EQUAL && selected) preview.getOrNull()?.get(id)?.let { Text(Ledger.formatMoney(it, currency), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                    if (mode == SplitMode.EQUAL) Text(if (selected) preview.getOrNull()?.get(id)?.let { Ledger.formatMoney(it, currency) } ?: "—" else "Not included", style = MaterialTheme.typography.bodyMedium, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                    else if (selected) WizardField(values[id].orEmpty(), { values = values + (id to it); error = "" }, when (mode) { SplitMode.PERCENT -> "%"; SplitMode.SHARES -> "Shares"; else -> currency }, "split_$id", !busy, numeric = true, signed = mode == SplitMode.ADJUSTMENT, modifier = Modifier.weight(1.1f))
                                }
                            }
                            if (preview.isFailure) Text(splitProgress(mode, amount, currency, participantIds, values) ?: preview.exceptionOrNull()?.message ?: "Enter shares to preview.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("split_total_feedback"))
                            else Text("Total ${Ledger.formatMoney(total(), currency)} · ${splitLabel(mode)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("split_total_feedback"))
                        }

                    }
                    else -> {
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth().testTag("expense_review_header"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("EXPENSE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(runCatching { Ledger.formatMoney(total(), currency) }.getOrDefault(amount), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Text("$currency · ${splitLabel(mode)}", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .16f))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(date, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    TextButton(onClick = { editingDate = !editingDate }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("expense_date_edit")) { Text(if (editingDate) "Done" else "Change date", color = MaterialTheme.colorScheme.onPrimaryContainer) }
                                }
                                if (editingDate) WizardField(date, { date = it; error = "" }, "Date (YYYY-MM-DD)", "expense_date", !busy)
                            }
                        }
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("People", Modifier.weight(1.4f), style = MaterialTheme.typography.titleSmall)
                                    Text("Paid", Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Share", Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                val paid = runCatching { contributions(total()) }.getOrDefault(emptyMap())
                                val owed = runCatching { shares(total()) }.getOrDefault(emptyMap())
                                (payerIds + participantIds).distinct().forEach { id ->
                                    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(name(id), Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium)
                                        Text(Ledger.formatMoney(paid[id] ?: 0L, currency), Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        Text(Ledger.formatMoney(owed[id] ?: 0L, currency), Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                        AppTextField(notes, { notes = it }, enabled = !busy, placeholder = { Text("Add a note, receipt details, or a reminder…") }, label = { Text("Notes · optional") }, singleLine = false, minLines = 3, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().testTag("expense_notes"))
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("expense_error"))
                Spacer(Modifier.height(4.dp))
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > 0) OutlinedButton(onClick = { error = ""; focus.clearFocus(); keyboard?.hide(); onStep(step - 1) }, enabled = !busy, shape = MaterialTheme.shapes.medium, modifier = Modifier.heightIn(min = 52.dp).testTag("expense_previous")) { Text("Back") }
            Button(onClick = {
                if (step < 2) advance() else runCatching {
                    validateDetails()
                    require(notes.trim().length <= 2000) { "Notes must be at most 2,000 characters." }
                    val parsed = total()
                    val paid = contributions(parsed)
                    Expense(draftId, group.id, title.trim(), parsed, paid.keys.first(), shares(parsed), expenseDate(), notes.trim(), existing?.deleted ?: false, currency, paid)
                }.onSuccess { error = ""; focus.clearFocus(); keyboard?.hide(); save(it) }.onFailure { error = it.message ?: "Check the expense details." }
            }, enabled = !busy, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag(if (step == 2) "expense_save" else "expense_next")) {
                Text(if (busy) "Saving…" else if (step == 2) if (existing == null) "Add expense" else "Save changes" else if (step == 1) "Review expense" else "Next: split the cost")
            }
        }
    }
    if (currencyPicker) AlertDialog(
        onDismissRequest = { currencyPicker = false },
        title = { Text("Expense currency") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WizardField(currencyQuery, { currencyQuery = it.uppercase() }, "Search currency code", "expense_currency_search", true)
                Text(if (allCurrencies || currencyQuery.isNotBlank()) "All currencies" else "Suggested · device region and recent expenses", style = MaterialTheme.typography.bodySmall)
                val matchingCurrencies = (if (allCurrencies || currencyQuery.isNotBlank()) supportedCurrencyCodes() else suggestedCurrencies).filter { it.contains(currencyQuery.trim(), ignoreCase = true) }
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    if (matchingCurrencies.isEmpty()) Text("No matching currency code.", style = MaterialTheme.typography.bodyMedium)
                    matchingCurrencies.forEach { code ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("expense_currency_$code").clickable { if (defaults?.currency != null && code != defaults.currency) values = emptyMap(); currency = code; currencyPicker = false; focus.clearFocus(); keyboard?.hide(); error = "" }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = code == currency, onClick = { if (defaults?.currency != null && code != defaults.currency) values = emptyMap(); currency = code; currencyPicker = false; focus.clearFocus(); keyboard?.hide(); error = "" })
                            Text(code)
                        }
                    }
                }
                if (!allCurrencies && currencyQuery.isBlank()) TextButton(onClick = { allCurrencies = true }, modifier = Modifier.testTag("expense_currency_more")) { Text("More currencies") }
            }
        },
        confirmButton = { TextButton(onClick = { currencyPicker = false }) { Text("Cancel") } },
    )
}

private fun splitLabel(mode: SplitMode) = when (mode) { SplitMode.EQUAL -> "Equally"; SplitMode.EXACT -> "Exact amounts"; SplitMode.PERCENT -> "Percentages"; SplitMode.SHARES -> "Shares"; SplitMode.ADJUSTMENT -> "Adjustments" }

@Composable private fun WizardField(value: String, change: (String) -> Unit, label: String, tag: String, enabled: Boolean, numeric: Boolean = false, signed: Boolean = false, singleLine: Boolean = true, modifier: Modifier = Modifier, placeholder: String? = null, minLines: Int = 1) {
    AppTextField(value, change, shape = MaterialTheme.shapes.small, textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = if (numeric) TextAlign.End else TextAlign.Start), label = { Text(label) }, placeholder = { if (placeholder != null) Text(placeholder) }, enabled = enabled, singleLine = singleLine, minLines = minLines, input = if (numeric) FieldInput.DECIMAL else when (tag) { "expense_currency_search" -> FieldInput.CURRENCY; "expense_date" -> FieldInput.DATE; else -> FieldInput.TEXT }, signed = signed, modifier = modifier.fillMaxWidth().testTag(tag))
}

@Composable private fun WizardSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/** Exact draft arithmetic works before currency or description is selected.
 * A suggestion only belongs to an empty payer field; accepting it is explicit.
 */
internal fun payerRemainder(amount: String, currency: String, payerIds: List<String>, amounts: Map<String, String>, id: String): String? = runCatching {
    require(id in payerIds && amounts[id].isNullOrBlank())
    fun number(text: String): BigDecimal {
        val clean = text.trim()
        val value = BigDecimal(clean)
        require(value >= BigDecimal.ZERO)
        // Once selected, enforce the same precision and Long bounds as saving.
        if (currency.isNotBlank()) Ledger.parseMoney(clean, currency)
        return value
    }
    val total = number(amount)
    require(total > BigDecimal.ZERO)
    val paid = payerIds.distinct().filter { it != id }.fold(BigDecimal.ZERO) { sum, payer ->
        sum + number(amounts[payer].orEmpty().ifBlank { "0" })
    }
    val remaining = total - paid
    require(remaining > BigDecimal.ZERO)
    if (currency.isBlank()) remaining.stripTrailingZeros().toPlainString()
    else remaining.setScale(currencyDigits(currency)).toPlainString()
}.getOrNull()

@Composable private fun WizardToggle(selected: Boolean, tag: String, label: String, enabled: Boolean, change: (Boolean) -> Unit) {
    Box(Modifier.width(56.dp).heightIn(min = 48.dp).testTag(tag).semantics { contentDescription = label }.toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = change), contentAlignment = Alignment.Center) {
        Checkbox(selected, onCheckedChange = null, enabled = enabled)
    }
}

private fun splitProgress(mode: SplitMode, amount: String, currency: String, ids: List<String>, values: Map<String, String>): String? = runCatching {
    when (mode) {
        SplitMode.EXACT -> {
            val total = BigInteger.valueOf(Ledger.parseMoney(amount, currency))
            val allocated = ids.fold(BigInteger.ZERO) { sum, id -> sum + BigInteger.valueOf(Ledger.parseMoney(values[id].orEmpty().ifBlank { "0" }, currency)) }
            val difference = total - allocated
            "${moneyText(allocated, currency)} allocated · ${moneyText(difference.abs(), currency)} ${if (difference.signum() >= 0) "left" else "over"}"
        }
        SplitMode.PERCENT -> {
            val assigned = ids.fold(BigDecimal.ZERO) { sum, id -> sum + BigDecimal(values[id].orEmpty().ifBlank { "0" }) }
            val remaining = BigDecimal("100") - assigned
            "${assigned.stripTrailingZeros().toPlainString()}% allocated · ${remaining.stripTrailingZeros().toPlainString()}% left"
        }
        else -> null
    }
}.getOrNull()
