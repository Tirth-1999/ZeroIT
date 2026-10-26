package org.fairshare.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import org.fairshare.app.platform.rememberReducedMotion
import androidx.compose.ui.unit.dp
import org.fairshare.app.domain.Ledger
import org.fairshare.app.model.AppState
import org.fairshare.app.model.Group
import org.fairshare.app.model.Settlement
import org.fairshare.app.model.MAX_TRANSACTION_MINOR
import org.fairshare.app.platform.calendarDateInput
import org.fairshare.app.platform.nowMillis
import org.fairshare.app.platform.newId
import org.fairshare.app.platform.parseCalendarDate
import org.fairshare.app.platform.todayDate

/** Only a complete, valid repayment suggestion may bypass the people chooser. */
internal fun isContextualSettlement(group: Group, from: String, to: String, amount: String, currency: String): Boolean =
    from != to && from in group.activeMemberIds && to in group.activeMemberIds && currency.isNotBlank() &&
        runCatching { Ledger.parseMoney(amount, currency) > 0 }.getOrDefault(false)

@Composable
internal fun CompactSettlementForm(
    state: AppState, group: Group, initialFrom: String, initialTo: String,
    initialAmount: String, initialCurrency: String, busy: Boolean, existing: Settlement? = null, save: (Settlement) -> Unit,
) {
    var currency by rememberSaveable(group.id, existing?.id, initialCurrency) { mutableStateOf(initialCurrency.ifBlank { group.currency }) }
    var from by rememberSaveable(group.id, existing?.id, initialFrom) { mutableStateOf(initialFrom.ifBlank { group.activeMemberIds.firstOrNull().orEmpty() }) }
    var to by rememberSaveable(group.id, existing?.id, initialTo) { mutableStateOf(initialTo.ifBlank { group.activeMemberIds.firstOrNull { it != from }.orEmpty() }) }
    var amount by rememberSaveable(group.id, existing?.id, initialAmount) { mutableStateOf(initialAmount) }
    var date by rememberSaveable(group.id, existing?.id) { mutableStateOf(existing?.let { calendarDateInput(it.date) } ?: todayDate()) }
    var error by rememberSaveable(group.id, existing?.id) { mutableStateOf("") }
    var choosingPeople by rememberSaveable(group.id, existing?.id, initialFrom, initialTo, initialAmount, initialCurrency) {
        mutableStateOf(!isContextualSettlement(group, initialFrom, initialTo, initialAmount, initialCurrency))
    }
    val reducedMotion = rememberReducedMotion()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun name(id: String) = state.members.firstOrNull { it.id == id }?.name ?: "Choose a person"
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().testTag("settlement_people_summary")) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettlementPerson(name(from), "Paid by", Modifier.weight(1f))
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "paid to", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                            }
                        }
                        SettlementPerson(name(to), "Received by", Modifier.weight(1f))
                    }
                    TextButton(onClick = { choosingPeople = !choosingPeople; focus.clearFocus(); keyboard?.hide() }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settlement_change_people")) {
                        Text(if (choosingPeople) "Done choosing people" else "Change people", textAlign = TextAlign.Center, modifier = Modifier.testTag("settlement_change_people_label"))
                    }
                }
            }
            AnimatedVisibility(visible = choosingPeople,
                enter = expandVertically(tween(if (reducedMotion) 0 else 200)) + fadeIn(tween(if (reducedMotion) 0 else 160)),
                exit = shrinkVertically(tween(if (reducedMotion) 0 else 200)) + fadeOut(tween(if (reducedMotion) 0 else 120))) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettlementPeople("Who paid?", "from", group.activeMemberIds, state, from, !busy) { from = it; error = "" }
                    SettlementPeople("Who received it?", "to", group.activeMemberIds, state, to, !busy) { to = it; error = "" }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(amount, { amount = it; error = "" }, label = { Text("Amount") }, singleLine = true, shape = MaterialTheme.shapes.small,
                    input = FieldInput.DECIMAL, enabled = !busy,
                    modifier = Modifier.weight(1f).testTag("settlement_amount"))
                AppTextField(currency, { currency = it.trim().uppercase(); amount = ""; error = "" }, label = { Text("Currency") }, input = FieldInput.CURRENCY, singleLine = true, shape = MaterialTheme.shapes.small,
                    enabled = !busy, modifier = Modifier.width(112.dp).testTag("settlement_currency"))
            }
            AppTextField(date, { date = it; error = "" }, label = { Text("Date (YYYY-MM-DD)") }, input = FieldInput.DATE, singleLine = true, shape = MaterialTheme.shapes.small,
                enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("settlement_date"))
            Text("For payments already made by cash or bank transfer. This updates balances; no money moves in ZeroIT.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("settlement_error"))
        }
        HorizontalDivider()
        Button(onClick = {
            runCatching {
                require(from in group.activeMemberIds && to in group.activeMemberIds) { "Choose two group members." }
                require(from != to) { "Choose two different members." }
                val parsed = Ledger.parseMoney(amount, currency)
                require(parsed > 0) { "Amount must be greater than zero." }
                require(parsed <= MAX_TRANSACTION_MINOR) { "Enter ${Ledger.formatMoney(MAX_TRANSACTION_MINOR, currency)} or less." }
                existing?.copy(fromId = from, toId = to, amountMinor = parsed, date = parseCalendarDate(date), currency = currency)
                    ?: Settlement(newId(), group.id, from, to, parsed, parseCalendarDate(date), currency = currency, createdAt = nowMillis())
            }.onSuccess { error = ""; focus.clearFocus(); keyboard?.hide(); save(it) }
                .onFailure { error = it.message ?: "Check the payment details." }
        }, enabled = !busy, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 52.dp).testTag("settlement_save")) {
            Text(if (existing == null) "Record payment" else "Save changes")
        }
    }
}

@Composable
private fun SettlementPeople(label: String, tag: String, ids: List<String>, state: AppState, selected: String, enabled: Boolean, select: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        ids.forEach { id ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settlement_${tag}_$id").clickable(enabled = enabled) { select(id) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == id, onClick = { select(id) }, enabled = enabled)
                Text(state.members.firstOrNull { it.id == id }?.name ?: "Unknown member")
            }
        }
    }
}

/** Equal-width people columns keep short and long names on the same centerline. */
@Composable
private fun SettlementPerson(name: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull() }.joinToString("").uppercase(),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}
