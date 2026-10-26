package org.fairshare.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Money communicates direction independently of a group's decorative accent. */
internal fun balanceMeaningColor(amount: Long, dark: Boolean, neutral: Color): Color = when {
    amount < 0L -> if (dark) Color(0xFFFFB4A8) else Color(0xFFB3261E)
    amount > 0L -> if (dark) Color(0xFF7ADBA0) else Color(0xFF176B3A)
    else -> neutral
}

@Composable
internal fun groupBalanceColor(amount: Long): Color {
    val colors = MaterialTheme.colorScheme
    return balanceMeaningColor(amount, colors.surface.luminance() < .5f, colors.onSurfaceVariant)
}
