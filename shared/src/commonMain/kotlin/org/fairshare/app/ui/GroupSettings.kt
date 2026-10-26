package org.fairshare.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.fairshare.app.domain.Ledger
import org.fairshare.app.model.*

internal val GroupTheme.label: String get() = when (this) {
    GroupTheme.CLASSIC -> "Classic"; GroupTheme.TRAVEL -> "Travel"; GroupTheme.HOME -> "Home"
    GroupTheme.DINING -> "Dining"; GroupTheme.CELEBRATION -> "Celebration"; GroupTheme.NATURE -> "Nature"
}
private val GroupTheme.icon: ImageVector get() = when (this) {
    GroupTheme.CLASSIC -> Icons.Default.Groups; GroupTheme.TRAVEL -> Icons.Default.Flight
    GroupTheme.HOME -> Icons.Default.Home; GroupTheme.DINING -> Icons.Default.Restaurant
    GroupTheme.CELEBRATION -> Icons.Default.Cake; GroupTheme.NATURE -> Icons.Default.Park
}

/** Change accents only: monetary debt/error colors retain their financial meaning. */
internal fun groupColors(base: ColorScheme, theme: GroupTheme, dark: Boolean): ColorScheme {
    if (theme == GroupTheme.CLASSIC) return base
    val colors = when (theme) {
        GroupTheme.TRAVEL -> if (dark) listOf(0xFFA2CAFF, 0xFF00315C, 0xFF1C4874, 0xFFD3E4FF) else listOf(0xFF245F96, 0xFFFFFFFF, 0xFFD3E4FF, 0xFF073354)
        GroupTheme.HOME -> if (dark) listOf(0xFFDDC0DD, 0xFF3D2844, 0xFF563E5D, 0xFFF9D8F7) else listOf(0xFF725278, 0xFFFFFFFF, 0xFFF2DAF4, 0xFF302036)
        GroupTheme.DINING -> if (dark) listOf(0xFFFFBC91, 0xFF512A0A, 0xFF6E3C18, 0xFFFFDCC5) else listOf(0xFF854B20, 0xFFFFFFFF, 0xFFFFDCC5, 0xFF381A04)
        GroupTheme.CELEBRATION -> if (dark) listOf(0xFFD3BDFF, 0xFF35225B, 0xFF4D3A73, 0xFFECDDFF) else listOf(0xFF695095, 0xFFFFFFFF, 0xFFEBDDFF, 0xFF2D184E)
        GroupTheme.NATURE -> if (dark) listOf(0xFFB9D398, 0xFF273C13, 0xFF3E5428, 0xFFD5EFB3) else listOf(0xFF4A6534, 0xFFFFFFFF, 0xFFD5E9BF, 0xFF203210)
        else -> return base
    }
    return base.copy(primary = Color(colors[0]), onPrimary = Color(colors[1]), primaryContainer = Color(colors[2]), onPrimaryContainer = Color(colors[3]))
}

@Composable
internal fun GroupBadge(group: Group, modifier: Modifier = Modifier.size(52.dp)) {
    val dark = when (LocalGroupAppearance.current) { "DARK" -> true; "LIGHT" -> false; else -> isSystemInDarkTheme() }
    val colors = groupColors(LocalGroupBaseColors.current, group.theme, dark)
    Surface(color = colors.primaryContainer, shape = RoundedCornerShape(12.dp), modifier = modifier.testTag("group_icon_${group.id}")) {
        Box(contentAlignment = Alignment.Center) {
            Icon(group.theme.icon, "${group.theme.label} group icon", tint = colors.onPrimaryContainer)
        }
    }
}

internal val LocalGroupBaseColors = staticCompositionLocalOf { lightColorScheme() }

internal val LocalGroupAppearance = staticCompositionLocalOf { "SYSTEM" }

@Composable
internal fun GroupSettingsScreen(state: AppState, group: Group, busy: Boolean, onUpdate: (String, GroupTheme) -> Unit, onMembers: () -> Unit, onBalances: () -> Unit, onSimplify: (Boolean) -> Unit, onDefaults: () -> Unit, onVisibility: (GroupVisibility) -> Unit) {
    var pendingSimplify by remember { mutableStateOf<Boolean?>(null) }
    var pendingVisibility by remember { mutableStateOf<GroupVisibility?>(null) }
    if (group.isHidden) {
        Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(group.name, style = MaterialTheme.typography.titleLarge)
            Text("This group is hidden on this device. Its history is retained. Restore it before making changes.")
            Button(onClick = { onVisibility(GroupVisibility.ACTIVE) }, enabled = !busy, modifier = Modifier.testTag("group_restore")) { Text("Restore group") }
        }
        return
    }
    var rename by rememberSaveable(group.id) { mutableStateOf(false) }
    var name by rememberSaveable(group.id) { mutableStateOf(group.name) }
    val currencies = remember(state, group.id) { Ledger.groupCurrencies(state, group.id) }
    val unsettledMembers = remember(state, group.id) {
        currencies.flatMap { Ledger.transfers(state, group.id, it) }.flatMap { listOf(it.fromId, it.toId) }.toSet()
    }
    val repaymentCounts = remember(state, group.id) {
        currencies.associateWith { currency -> Ledger.directTransfers(state, group.id, currency).size to Ledger.simplifiedTransfers(state, group.id, currency).size }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp).testTag("group_settings_screen"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GroupBadge(group)
            Column(Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("${group.activeMemberIds.size} members", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { name = group.name; rename = true }, enabled = !busy, modifier = Modifier.testTag("group_rename")) { Text("Edit") }
        }
        OutlinedButton(onClick = onMembers, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("group_settings_members")) {
            Icon(Icons.Default.Groups, null); Spacer(Modifier.width(8.dp)); Text("Manage members")
        }
        Text("Group theme", style = MaterialTheme.typography.titleMedium)
        Text("Choose a color and matching icon for this group. Follows your app's light or dark appearance.", style = MaterialTheme.typography.bodyMedium)
        GroupTheme.entries.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { theme ->
                    val preview = group.copy(theme = theme)
                    OutlinedCard(onClick = { onUpdate(group.name, theme) }, enabled = !busy, modifier = Modifier.weight(1f).testTag("group_theme_${theme.name}").semantics { selected = group.theme == theme }) {
                        Row(Modifier.padding(10.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GroupBadge(preview, Modifier.size(36.dp))
                            Text(theme.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            if (group.theme == theme) Icon(Icons.Default.Check, "Selected", Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        Text("Expense currencies", style = MaterialTheme.typography.titleMedium)
        Text(currencies.joinToString(" · "), modifier = Modifier.testTag("group_settings_currencies"))
        Text("Each expense has its own currency. Balances stay separate; choosing a theme does not change amounts or currencies.", style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        Text("Simplified settlements", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Simplify group debts", Modifier.weight(1f))
            Switch(checked = group.simplifyDebts, onCheckedChange = { pendingSimplify = it }, enabled = !busy, modifier = Modifier.testTag("group_simplify_debts"))
        }
        Text(if (group.simplifyDebts) "Combine debts within each currency to suggest fewer repayments. Everyone's net balance stays the same." else "Keep the original payer-to-participant debts, netting payments only between the same two people.", style = MaterialTheme.typography.bodyMedium)
        currencies.forEach { currency ->
            val (direct, simplified) = repaymentCounts.getValue(currency)
            Text("$currency · $simplified simplified / $direct original repayments", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("group_debt_comparison_$currency"))
        }
        OutlinedButton(onClick = onBalances, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("group_settings_balances")) { Text("View balances and suggestions") }
        HorizontalDivider()
        Text("My default split", style = MaterialTheme.typography.titleMedium)
        Text("Personal defaults for new expenses you add to this group. Existing expenses never change.", style = MaterialTheme.typography.bodyMedium)
        Text(group.expenseDefaults?.let { "${state.members.firstOrNull { p -> p.id == it.payerId }?.name ?: "You"} pays · ${it.splitMode.name.lowercase()}" } ?: "You pay · split equally", modifier = Modifier.testTag("group_defaults_summary"))
        OutlinedButton(onClick = onDefaults, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("group_defaults")) { Text("Edit my defaults") }
        HorizontalDivider()
        val canLeave = SELF_ID !in unsettledMembers
        val canDelete = unsettledMembers.isEmpty()
        OutlinedButton(onClick = { pendingVisibility = GroupVisibility.LEFT }, enabled = !busy && canLeave, modifier = Modifier.fillMaxWidth().testTag("group_leave")) { Text("Leave group on this device") }
        if (!canLeave) Text("Settle your outstanding debts in every currency before leaving.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { pendingVisibility = GroupVisibility.DELETED }, enabled = !busy && canDelete, modifier = Modifier.fillMaxWidth().testTag("group_delete")) { Text("Delete group") }
        if (!canDelete) Text("Everyone must be settled up in every currency before deleting this group.", style = MaterialTheme.typography.bodySmall)
        Text("Left or deleted groups remain recoverable in Account → Hidden groups. Expense and payment history is retained.", style = MaterialTheme.typography.bodySmall)
        Text("Settings are saved on this device. Shared invitations and group sync are planned.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
    }
    pendingSimplify?.let { enabled ->
        AlertDialog(onDismissRequest = { if (!busy) pendingSimplify = null }, title = { Text(if (enabled) "Simplify group debts?" else "Keep original debts?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Suggestions may change who pays whom. Balances, expenses and recorded payments stay unchanged.")
                Ledger.groupCurrencies(state, group.id).forEach { currency ->
                    val original = Ledger.directTransfers(state, group.id, currency).size
                    val simple = Ledger.simplifiedTransfers(state, group.id, currency).size
                    Text("$currency: ${if (enabled) original else simple} → ${if (enabled) simple else original} repayments")
                }
            }
        }, confirmButton = { TextButton(onClick = { pendingSimplify = null; onSimplify(enabled) }, enabled = !busy, modifier = Modifier.testTag("group_simplify_confirm")) { Text("Apply") } }, dismissButton = { TextButton(onClick = { pendingSimplify = null }, enabled = !busy) { Text("Cancel") } })
    }
    pendingVisibility?.let { visibility ->
        AlertDialog(onDismissRequest = { if (!busy) pendingVisibility = null }, title = { Text(if (visibility == GroupVisibility.LEFT) "Leave on this device?" else "Delete group?") }, text = { Text("The group will be hidden on this device. Its history is kept, and you can restore it from Account → Hidden groups. No message is sent to anyone.") }, confirmButton = { TextButton(onClick = { pendingVisibility = null; onVisibility(visibility) }, enabled = !busy, modifier = Modifier.testTag("group_visibility_confirm")) { Text("Confirm") } }, dismissButton = { TextButton(onClick = { pendingVisibility = null }, enabled = !busy) { Text("Cancel") } })
    }
    if (rename) AlertDialog(onDismissRequest = { if (!busy) rename = false }, title = { Text("Group name") }, text = {
        AppTextField(name, { name = it }, singleLine = true, input = FieldInput.NAME, label = { Text("Name") }, modifier = Modifier.testTag("group_settings_name"))
    }, confirmButton = {
        TextButton(onClick = { onUpdate(name, group.theme); rename = false }, enabled = !busy && name.trim().isNotEmpty() && name.trim().length <= 80, modifier = Modifier.testTag("group_settings_save_name")) { Text("Save") }
    }, dismissButton = { TextButton(onClick = { rename = false }, enabled = !busy) { Text("Cancel") } })
}
