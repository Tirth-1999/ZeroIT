package org.fairshare.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** The home action stays in one place while its two destinations appear above it. */
@Composable
internal fun GroupsAddMenu(
    expanded: Boolean,
    reducedMotion: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onAddGroup: () -> Unit,
    onAddExpense: () -> Unit
) {
    val duration = if (reducedMotion) 0 else 180
    val rotation by animateFloatAsState(if (expanded) 45f else 0f, tween(duration), label = "Add menu toggle")
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedVisibility(visible = expanded, enter = fadeIn(tween(duration)), exit = fadeOut(tween(duration))) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AddActionBubble("Add group", "home_add_group", enabled && expanded, onAddGroup) {
                    Icon(Icons.Default.GroupAdd, null, Modifier.size(22.dp))
                }
                AddActionBubble("Add expense", "global_add_expense", enabled && expanded, onAddExpense) {
                    Icon(Icons.Default.ReceiptLong, null, Modifier.size(22.dp))
                }
            }
        }
        FloatingActionButton(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            onClick = { if (enabled) onToggle() },
            modifier = Modifier.testTag("home_add_menu").semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }
        ) {
            Icon(Icons.Default.Add, if (expanded) "Close add menu" else "Add group or expense", Modifier.rotate(rotation))
        }
    }
}

@Composable
private fun AddActionBubble(
    text: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    ElevatedButton(
        colors = ButtonDefaults.elevatedButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        modifier = Modifier.widthIn(max = 280.dp).defaultMinSize(minHeight = 48.dp).testTag(tag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            icon()
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
