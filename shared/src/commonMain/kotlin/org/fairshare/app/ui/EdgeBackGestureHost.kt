package org.fairshare.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.PredictiveBackHandler
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fairshare.app.platform.rememberReducedMotion
import kotlin.math.abs
import kotlin.math.min

/**
 * Only starts within the outer 18dp; vertical scrolling and interior row swipes remain untouched.
 * The callback is the app's normal back action, including its unsaved-draft confirmation.
 * Native predictive-back events feed the same rendering; pointer tracking supplements platforms
 * or navigation modes where the system does not own the edge gesture.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EdgeBackGestureHost(
    enabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val currentBack by rememberUpdatedState(onBack)
    val reducedMotion = rememberReducedMotion()
    val density = LocalDensity.current
    val edgeWidth = with(density) { 18.dp.toPx() }
    val minimumCommit = with(density) { 76.dp.toPx() }
    val maximumBulge = with(density) { 54.dp.toPx() }
    val indicatorRadius = with(density) { 42.dp.toPx() }
    val scope = rememberCoroutineScope()
    val returnFraction = remember { Animatable(1f) }
    var active by remember { mutableStateOf(false) }
    var returning by remember { mutableStateOf(false) }
    var distance by remember { mutableFloatStateOf(0f) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var left by remember { mutableStateOf(true) }
    var originY by remember { mutableFloatStateOf(0f) }
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(enabled) {
        if (!enabled) { active = false; returning = false; distance = 0f; returnFraction.snapTo(1f) }
    }
    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            returnFraction.snapTo(1f)
            events.collect { event ->
                active = true
                returning = false
                left = event.swipeEdge == 0 // AndroidX BackEventCompat.EDGE_LEFT; common alias is internal.
                fingerY = event.touchY - originY
                distance = event.progress.coerceIn(0f, 1f) * minimumCommit * 1.5f
            }
            active = false
            distance = 0f
            currentBack()
        } catch (cancelled: CancellationException) {
            active = false
            returning = true
            withContext(NonCancellable) {
                returnFraction.animateTo(0f, tween(if (reducedMotion) 0 else 180, easing = FastOutSlowInEasing))
                distance = 0f
                returning = false
                returnFraction.snapTo(1f)
            }
            throw cancelled
        }
    }
    Box(modifier.fillMaxSize().onGloballyPositioned { originY = it.positionInRoot().y }.then(if (!enabled) Modifier else Modifier.pointerInput(edgeWidth, minimumCommit, reducedMotion) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (returning || (down.position.x > edgeWidth && down.position.x < size.width - edgeWidth)) return@awaitEachGesture
            val fromLeft = down.position.x <= edgeWidth
            val direction = if (fromLeft) 1f else -1f
            var claimed = false
            var cancelled = false
            var inward = 0f
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.count { it.pressed } > 1) { cancelled = true; break }
                val delta = change.position - down.position
                inward = delta.x * direction
                if (!claimed) {
                    if (abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x)) { cancelled = true; break }
                    if (inward > viewConfiguration.touchSlop && inward > abs(delta.y) * 1.2f) {
                        claimed = true; left = fromLeft; active = true
                    }
                }
                if (claimed) {
                    distance = inward.coerceAtLeast(0f)
                    fingerY = change.position.y.coerceIn(0f, size.height.toFloat())
                    change.consume()
                }
                if (!change.pressed) break
            } while (true)
            if (claimed) {
                active = false
                if (!cancelled && inward >= min(minimumCommit, size.width * .27f)) {
                    distance = 0f
                    currentBack()
                } else {
                    returning = true
                    scope.launch {
                        try { returnFraction.animateTo(0f, tween(if (reducedMotion) 0 else 180, easing = FastOutSlowInEasing)) }
                        finally { distance = 0f; returning = false; returnFraction.snapTo(1f) }
                    }
                }
            }
        }
    })) {
        val fraction = if (returning) returnFraction.value else 1f
        val progress = (distance / minimumCommit).coerceIn(0f, 1f) * fraction
        Box(Modifier.fillMaxSize().graphicsLayer {
            // Keep continuous finger tracking; avoid moving text for Reduce Motion users.
            if (!reducedMotion) {
                translationX = (if (left) 1f else -1f) * min(distance * .09f, 18.dp.toPx()) * fraction
                scaleX = 1f - .012f * progress
                scaleY = scaleX
            }
        }, content = content)
        if ((active || returning) && distance > 0f) Canvas(Modifier.fillMaxSize().testTag("edge_back_feedback")) {
            val bulge = min(distance * .48f, maximumBulge) * fraction
            val y = fingerY
            val x = if (left) 0f else size.width
            val direction = if (left) 1f else -1f
            val path = Path().apply {
                moveTo(x, y - indicatorRadius * 1.7f)
                cubicTo(x, y - indicatorRadius * .7f, x + direction * bulge, y - indicatorRadius * .8f, x + direction * bulge, y)
                cubicTo(x + direction * bulge, y + indicatorRadius * .8f, x, y + indicatorRadius * .7f, x, y + indicatorRadius * 1.7f)
                close()
            }
            drawPath(path, colors.primaryContainer.copy(alpha = .96f))
            if (bulge > 16.dp.toPx()) {
                val arrowX = x + direction * bulge * .55f
                val half = 5.dp.toPx()
                val arrow = Path().apply {
                    moveTo(arrowX + direction * half * .45f, y - half)
                    lineTo(arrowX - direction * half * .45f, y)
                    lineTo(arrowX + direction * half * .45f, y + half)
                }
                drawPath(arrow, colors.onPrimaryContainer, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}
