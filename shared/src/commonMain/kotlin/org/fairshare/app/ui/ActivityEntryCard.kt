package org.fairshare.app.ui

import androidx.compose.animation.AnimatedVisibility
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.fairshare.app.model.*
import org.fairshare.app.platform.activityDateLabel

/** Explicit record links or known deterministic demo event IDs; never parse historical prose. */
internal fun editableActivityExpense(state: AppState, activity: Activity): Expense? {
    if (activity.settlementId != null) return null
    val expense = state.expenses.firstOrNull {
        (it.id == activity.expenseId || (activity.expenseId == null && it.id.startsWith("demo-scenarios-") && activity.id == "${it.id}-added")) && it.groupId == activity.groupId && !it.deleted
    } ?: return null
    return expense.takeIf { state.groups.any { it.id == expense.groupId && !it.isHidden } }
}

internal fun editableActivitySettlement(state: AppState, activity: Activity): Settlement? {
    if (activity.expenseId != null) return null
    val settlement = state.settlements.firstOrNull {
        (it.id == activity.settlementId || (activity.settlementId == null && it.id.startsWith("demo-scenarios-") && activity.id == "${it.id}-recorded")) && it.groupId == activity.groupId && !it.deleted
    } ?: return null
    return settlement.takeIf { state.groups.any { it.id == settlement.groupId && !it.isHidden } }
}

/** Restore only the deleted expense explicitly linked by its deletion event. */
internal fun restorableActivityExpense(state: AppState, activity: Activity): Expense? {
    if (activity.settlementId != null || activity.expenseId == null || !activity.message.startsWith("Deleted ")) return null
    return state.expenses.firstOrNull { expense ->
        expense.id == activity.expenseId && expense.groupId == activity.groupId && expense.deleted &&
            state.groups.any { it.id == expense.groupId && !it.isHidden }
    }
}

/** Stable for timestamp ties: a later insertion remains ahead of an earlier one. */
internal fun expensesNewestAddedFirst(expenses: List<Expense>): List<Expense> = expenses.asReversed().sortedByDescending { it.createdAt }

/** Keep transaction activity scannable; full shares remain available in the linked expense. */
private fun compactActivityMessage(message: String): String {
    fun expenseSummary(text: String): String = text
        .substringBefore(", paid by")
        .substringBefore("; shares:")
        .substringBefore("; notes:")
        .substringBefore("; ")
    return when {
        message.startsWith("Added ") -> "Added ${expenseSummary(message.removePrefix("Added "))}"
        message.startsWith("Deleted ") -> "Deleted ${expenseSummary(message.removePrefix("Deleted "))}"
        message.startsWith("Restored ") -> "Restored ${expenseSummary(message.removePrefix("Restored "))}"
        message.startsWith("Edited expense: ") -> {
            val changed = message.removePrefix("Edited expense: ").substringAfter(" → ", message.removePrefix("Edited expense: "))
            "Edited ${expenseSummary(changed)}"
        }
        else -> message
    }
}

/** A swipe requests an action. History remains immutable and deletion always needs confirmation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityEntryCard(
    state: AppState, activity: Activity, busy: Boolean,
    onEditExpense: (String) -> Unit, onDeleteExpense: (String) -> Unit,
    contextLabel: String? = null, onContext: (() -> Unit)? = null,
    onEditSettlement: ((String) -> Unit)? = null, onDeleteSettlement: ((String) -> Unit)? = null,
    onRestoreExpense: ((String) -> Unit)? = null,
) {
    val expense = editableActivityExpense(state, activity)
    val restorableExpense = restorableActivityExpense(state, activity)
    val settlement = editableActivitySettlement(state, activity)
    val editAction: (() -> Unit)? = when {
        expense != null -> { { onEditExpense(expense.id) } }
        settlement != null && onEditSettlement != null -> { { onEditSettlement(settlement.id) } }
        else -> null
    }
    val deleteAction: (() -> Unit)? = when {
        expense != null -> { { onDeleteExpense(expense.id) } }
        settlement != null && onDeleteSettlement != null -> { { onDeleteSettlement(settlement.id) } }
        else -> null
    }
    val restoreAction: (() -> Unit)? = restorableExpense?.takeIf { onRestoreExpense != null }?.let { target -> { onRestoreExpense?.invoke(target.id) } }
    val subject = if (settlement != null) "payment record" else "expense"
    val historyReason = when {
        state.groups.any { it.id == activity.groupId && it.isHidden } -> "Restore this group to edit its transactions"
        activity.expenseId != null && activity.settlementId != null -> "History has no single transaction to edit"
        activity.expenseId != null -> if (state.expenses.any { it.id == activity.expenseId && it.groupId == activity.groupId && it.deleted }) "Expense deleted" else "Linked expense is unavailable"
        activity.settlementId != null -> if (state.settlements.any { it.id == activity.settlementId && it.groupId == activity.groupId && it.deleted }) "Payment record deleted" else "Linked payment is unavailable"
        else -> "Informational history · no linked transaction"
    }
    val deletedExpense = activity.settlementId == null && state.expenses.any {
        (it.id == activity.expenseId || (activity.expenseId == null && it.id.startsWith("demo-scenarios-") && activity.id == "${it.id}-added")) && it.groupId == activity.groupId && it.deleted
    }
    val deletedPayment = activity.expenseId == null && state.settlements.any {
        (it.id == activity.settlementId || (activity.settlementId == null && it.id.startsWith("demo-scenarios-") && activity.id == "${it.id}-recorded")) && it.groupId == activity.groupId && it.deleted
    }
    val deletedTransaction = deletedExpense || deletedPayment
    val deletedHint = when {
        state.groups.any { it.id == activity.groupId && it.isHidden } -> historyReason
        deletedExpense && restoreAction != null -> "Deleted expenses can’t be modified. Restore this expense first."
        deletedExpense -> "This expense was deleted. Restore it from its deletion entry in Activity."
        else -> "This payment record was deleted and can’t be modified."
    }
    var swipeHint by remember(activity.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(swipeHint) {
        if (swipeHint != null) { delay(3500); swipeHint = null }
    }
    val cardColor = if (MaterialTheme.colorScheme.surface.luminance() < .5f)
        MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer
    var confirmDelete by remember(activity.id) { mutableStateOf(false) }
    var menuExpanded by remember(activity.id) { mutableStateOf(false) }
    val currentEdit by rememberUpdatedState(editAction)
    val currentDelete by rememberUpdatedState(deleteAction)
    val currentBusy by rememberUpdatedState(busy)
    val currentDeleted by rememberUpdatedState(deletedTransaction)
    val currentDeletedHint by rememberUpdatedState(deletedHint)
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * .45f },
        confirmValueChange = { value ->
            if (!currentBusy) when (value) {
                SwipeToDismissBoxValue.StartToEnd -> if (currentEdit != null) currentEdit?.invoke() else if (currentDeleted) swipeHint = currentDeletedHint
                SwipeToDismissBoxValue.EndToStart -> if (currentDelete != null) confirmDelete = true else if (currentDeleted) swipeHint = currentDeletedHint
                else -> Unit
            }
            false // An activity row is never removed or rewritten by a swipe.
        },
    )
    val available = (editAction != null || deleteAction != null || restoreAction != null) && !busy
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = (editAction != null || deletedTransaction) && !busy,
        enableDismissFromEndToStart = (deleteAction != null || deletedTransaction) && !busy,
        modifier = Modifier.fillMaxWidth().testTag("activity_${activity.id}").then(if (onContext != null) Modifier.clickable(enabled = !busy, onClickLabel = "Open group", onClick = onContext) else Modifier),
        backgroundContent = {
            val deleting = dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Surface(shape = RoundedCornerShape(18.dp), color = if (deletedTransaction) MaterialTheme.colorScheme.surfaceContainerHigh else if (deleting) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxSize().clearAndSetSemantics { }) {
                Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalArrangement = if (deleting) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (deletedTransaction) Icons.Default.Info else if (deleting) Icons.Default.DeleteOutline else Icons.Default.Edit, null)
                    Spacer(Modifier.width(8.dp)); Text(if (deletedTransaction) "Deleted item" else if (deleting) "Delete $subject" else "Edit $subject")
                }
            }
        },
    ) {
        Surface(shape = RoundedCornerShape(16.dp), color = if (settlement != null) settlementTileColor(cardColor, state.groups.firstOrNull { it.id == activity.groupId }?.theme) else cardColor, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().semantics {
            if (available) customActions = listOfNotNull(
                editAction?.let { CustomAccessibilityAction("Edit linked $subject") { currentEdit?.invoke(); true } },
                deleteAction?.let { CustomAccessibilityAction("Delete linked $subject") { confirmDelete = true; true } },
                restoreAction?.let { CustomAccessibilityAction("Restore deleted expense") { restoreAction.invoke(); true } },
            )
        }) {
            Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(compactActivityMessage(activity.message), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(listOfNotNull(contextLabel, activityDateLabel(activity.at)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AnimatedVisibility(swipeHint != null) {
                        Text(swipeHint.orEmpty(), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("activity_swipe_hint_${activity.id}").semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }, enabled = !busy, modifier = Modifier.testTag("activity_actions_${activity.id}")) { Icon(Icons.Default.MoreVert, "Activity actions") }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        if (onContext != null) DropdownMenuItem(text = { Text("View group") }, leadingIcon = { Icon(Icons.Default.Groups, null) }, onClick = { menuExpanded = false; onContext() }, enabled = !busy, modifier = Modifier.testTag("activity_context_${activity.id}"))
                        if (editAction == null && deleteAction == null && restoreAction == null) DropdownMenuItem(text = { Text(historyReason) }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = {}, enabled = false, modifier = Modifier.testTag("activity_history_${activity.id}"))
                        if (editAction != null) DropdownMenuItem(text = { Text("Edit $subject") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menuExpanded = false; currentEdit?.invoke() }, enabled = !busy, modifier = Modifier.testTag("activity_edit_${activity.id}"))
                        if (deleteAction != null) DropdownMenuItem(text = { Text("Delete $subject") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = { menuExpanded = false; confirmDelete = true }, enabled = !busy, modifier = Modifier.testTag("activity_delete_${activity.id}"))
                        if (restoreAction != null) DropdownMenuItem(text = { Text("Restore expense") }, leadingIcon = { Icon(Icons.Default.Restore, null) }, onClick = { menuExpanded = false; restoreAction.invoke() }, enabled = !busy, modifier = Modifier.testTag("activity_restore_${activity.id}"))
                    }
                }
            }
        }
    }
    if (confirmDelete && deleteAction != null) AlertDialog(
        onDismissRequest = { if (!busy) confirmDelete = false },
        title = { Text(if (expense != null) "Delete ${expense.title}?" else "Delete this payment record?") },
        text = { Text(if (expense != null) "Balances will be recalculated. You can restore the expense from its deletion entry in Activity. The activity history stays unchanged." else "Balances will be recalculated. This records a correction in history; it does not cancel a transfer made outside ZeroIT.") },
        confirmButton = { TextButton(onClick = { val action = currentDelete; confirmDelete = false; action?.invoke() }, enabled = !busy, modifier = Modifier.testTag("activity_confirm_delete_${activity.id}")) { Text("Delete $subject", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }, enabled = !busy) { Text("Cancel") } },
    )
}
