package org.fairshare.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Native rendering of assets/group-people SVG geometry, shared by Android and iOS. */
@Composable
internal fun GroupPeopleIllustration(memberCount: Int, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val foreground = Color(if (dark) 0xFFD4D4D4 else 0xFF525252)
    val background = Color(if (dark) 0xFFA3A3A3 else 0xFF737373)
    val visible = memberCount.coerceIn(0, 6)
    Box(modifier.size(104.dp, 72.dp).testTag("group_people_illustration")) {
        // Decorative: the actionable member count is already announced in the toolbar.
        Canvas(Modifier.matchParentSize()) {
            scale(size.width / 120f, size.height / 76f, pivot = Offset.Zero) {
                val rows = listOf((visible - 3).coerceAtLeast(0), visible.coerceAtMost(3))
                rows.forEachIndexed { row, count ->
                    repeat(count) { index ->
                        val x = 60f + (index - (count - 1) / 2f) * 32f
                        val y = if (row == 0) 15f else 36f
                        val color = if (row == 0) background else foreground
                        drawCircle(color, 8f, Offset(x, y))
                        drawPath(Path().apply {
                            moveTo(x - 14f, y + 27f)
                            lineTo(x - 14f, y + 22f)
                            cubicTo(x - 14f, y + 6f, x + 14f, y + 6f, x + 14f, y + 22f)
                            lineTo(x + 14f, y + 27f)
                            close()
                        }, color)
                    }
                }
            }
        }
        if (memberCount > 6) Text("+${memberCount - 6}", modifier = Modifier.align(Alignment.TopEnd),
            style = MaterialTheme.typography.labelSmall, color = foreground)
    }
}
