package org.fairshare.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.fairshare.app.domain.Ledger
import org.fairshare.app.model.*
import org.fairshare.app.platform.currencyDigits

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GroupDefaultsScreen(state: AppState, group: Group, busy: Boolean, onSave: (ExpenseDefaults?) -> Unit) {
    val original = group.expenseDefaults
    var payer by rememberSaveable(group.id) { mutableStateOf(original?.payerId ?: SELF_ID) }
    var participants by rememberSaveable(group.id) { mutableStateOf(original?.participantIds?.ifEmpty { group.activeMemberIds } ?: group.activeMemberIds) }
    var modeName by rememberSaveable(group.id) { mutableStateOf(original?.splitMode?.name ?: SplitMode.EQUAL.name) }
    var values by rememberSaveable(group.id) { mutableStateOf(original?.values ?: emptyMap<String, String>()) }
    var currency by rememberSaveable(group.id) { mutableStateOf(original?.currency.orEmpty()) }
    var error by rememberSaveable(group.id) { mutableStateOf("") }
    val mode = SplitMode.valueOf(modeName)
    val fixed = mode == SplitMode.EXACT || mode == SplitMode.ADJUSTMENT
    fun name(id: String) = state.members.firstOrNull { it.id == id }?.name ?: "Unknown member"
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(22.dp).testTag("group_defaults_screen"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("My defaults · ${group.name}", style = MaterialTheme.typography.titleMedium)
        Text("Only new expenses you add use these preferences. You can change any choice in the expense editor. New expenses start in the group’s default currency; you can change it for each expense.")
        Text("Default payer", style = MaterialTheme.typography.titleMedium)
        group.activeMemberIds.forEach { id ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("default_payer_$id").clickable(enabled = !busy) { payer = id }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(payer == id, onClick = { payer = id }, enabled = !busy)
                Text(name(id))
            }
        }
        Text("Default participants", style = MaterialTheme.typography.titleMedium)
        group.activeMemberIds.forEach { id ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("default_participant_$id").clickable(enabled = !busy) { participants = if (id in participants) participants - id else participants + id }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(id in participants, onCheckedChange = { checked -> participants = if (checked) participants + id else participants - id }, enabled = !busy)
                Text(name(id))
            }
        }
        Text("Split method", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SplitMode.entries.forEach { option ->
                FilterChip(selected = mode == option, onClick = { modeName = option.name; values = emptyMap(); error = "" }, label = { Text(when (option) { SplitMode.EQUAL -> "Equally"; SplitMode.EXACT -> "Exact amounts"; SplitMode.PERCENT -> "Percentages"; SplitMode.SHARES -> "Shares"; SplitMode.ADJUSTMENT -> "Adjustments" }) }, enabled = !busy, modifier = Modifier.testTag("default_mode_${option.name}"))
            }
        }
        if (fixed) {
            AppTextField(currency, { currency = it.trim().uppercase(); error = "" }, label = { Text("Template currency (ISO code)") }, input = FieldInput.CURRENCY, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("default_currency"))
            Text("Saved amounts apply when the expense uses this currency. Other currencies clear them; no conversion takes place.", style = MaterialTheme.typography.bodySmall)
        }
        if (mode != SplitMode.EQUAL) participants.forEach { id ->
            AppTextField(values[id].orEmpty(), { values = values + (id to it); error = "" }, label = { Text("${name(id)} · ${when (mode) { SplitMode.PERCENT -> "%"; SplitMode.SHARES -> "shares"; SplitMode.ADJUSTMENT -> "adjustment"; else -> "amount" }}") }, singleLine = true, input = FieldInput.DECIMAL, signed = mode == SplitMode.ADJUSTMENT, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("default_value_$id"))
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("defaults_error"))
        Button(onClick = {
            runCatching {
                require(participants.isNotEmpty()) { "Choose at least one participant." }
                val selectedValues = if (mode == SplitMode.EQUAL) emptyMap() else participants.associateWith { values[it].orEmpty().ifBlank { "0" } }
                if (fixed) {
                    currencyDigits(currency)
                    val amounts = selectedValues.values.map { Ledger.parseMoney(it, currency) }
                    if (mode == SplitMode.EXACT) require(amounts.all { it >= 0 } && amounts.any { it > 0 }) { "Enter nonnegative amounts with a positive total." }
                } else Ledger.split(10000, participants, mode, selectedValues)
                ExpenseDefaults(payer, mode, participants, selectedValues, if (fixed) currency else null)
            }.onSuccess { error = ""; onSave(it) }.onFailure { error = it.message ?: "Check the default split." }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("defaults_save")) { Text("Save my defaults") }
        OutlinedButton(onClick = { onSave(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("defaults_reset")) { Text("Reset to you paying, split equally") }
    }
}
