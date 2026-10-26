package org.fairshare.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.fairshare.app.platform.rememberReducedMotion

internal data class SaveCelebrationEvent(val id: Long, val message: String)

/** Full-screen, themed transaction acknowledgement. The destination stays composed underneath. */
@Composable
internal fun SaveCelebration(event: SaveCelebrationEvent?, dismiss: (Long) -> Unit) {
    if (event == null) return
    val reduced = rememberReducedMotion()
    val progress = remember(event.id) { Animatable(0f) }
    val accent = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    LaunchedEffect(event.id, reduced) {
        if (reduced) {
            progress.snapTo(.62f)
            delay(450)
        } else {
            // One short entrance, a readable hold, and a soft exit. No loops or waves.
            progress.animateTo(1f, tween(850, easing = LinearEasing))
        }
        dismiss(event.id)
    }
    Box(
        Modifier.fillMaxSize()
            .testTag("save_celebration")
            .graphicsLayer {
                val value = progress.value
                val enter = FastOutSlowInEasing.transform((value / .14f).coerceIn(0f, 1f))
                val exit = FastOutSlowInEasing.transform(((value - .78f) / .22f).coerceIn(0f, 1f))
                alpha = if (reduced) 1f else enter * (1f - exit)
            }
            .background(background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Continue") { dismiss(event.id) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp).graphicsLayer {
                val value = if (reduced) .62f else progress.value
                val enter = FastOutSlowInEasing.transform((value / .22f).coerceIn(0f, 1f))
                scaleX = if (reduced) 1f else .96f + .04f * enter
                scaleY = scaleX
                translationY = if (reduced) 0f else (1f - enter) * 6.dp.toPx()
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val value = if (reduced) 1f else FastOutSlowInEasing.transform((progress.value / .3f).coerceIn(0f, 1f))
                    val radius = size.minDimension * .39f
                    drawCircle(accent.copy(alpha = .12f), radius)
                    drawArc(accent, -90f, 360f * value, false,
                        Offset(center.x - radius, center.y - radius),
                        androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                        style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                    val check = if (reduced) 1f else FastOutSlowInEasing.transform(((progress.value - .08f) / .24f).coerceIn(0f, 1f))
                    val a = Offset(center.x - radius * .38f, center.y + radius * .02f)
                    val b = Offset(center.x - radius * .08f, center.y + radius * .32f)
                    val c = Offset(center.x + radius * .46f, center.y - radius * .34f)
                    val path = Path().apply {
                        moveTo(a.x, a.y)
                        if (check <= .42f) lineTo(a.x + (b.x - a.x) * check / .42f, a.y + (b.y - a.y) * check / .42f)
                        else {
                            lineTo(b.x, b.y)
                            val tail = (check - .42f) / .58f
                            lineTo(b.x + (c.x - b.x) * tail, b.y + (c.y - b.y) * tail)
                        }
                    }
                    drawPath(path, accent, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            Text(event.message, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                color = onSurface, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })

        }
    }
}
