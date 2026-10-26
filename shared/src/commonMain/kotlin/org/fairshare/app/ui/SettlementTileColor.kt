package org.fairshare.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.fairshare.app.model.GroupTheme

/** A quiet accent wash preserves the neutral surface and readable foreground. */
@Composable
internal fun settlementTileColor(
    base: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    theme: GroupTheme? = null,
): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val accent = if (theme == null) MaterialTheme.colorScheme.primary
        else groupColors(LocalGroupBaseColors.current, theme, dark).primary
    return accent.copy(alpha = if (dark) .10f else .07f).compositeOver(base)
}
