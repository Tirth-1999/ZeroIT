package org.fairshare.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.fairshare.app.platform.rememberReducedMotion

/** Shared, glass-inspired navigation. Uses theme surfaces, not a native Liquid Glass material. */
@Composable
internal fun FloatingBottomNavigation(
    selectedTab: String,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    // Only decoration may extend into the system-safe bottom area. The actual
    // tab targets end 10dp above the pill's bottom (4dp frame + 6dp footroom),
    // so credit that clearance once and keep the full >=48dp target safe.
    val dockBottomGap = floatingNavigationBottomGap(bottomInset)
    val reducedMotion = rememberReducedMotion()
    val tabs = remember { listOf("groups", "friends", "activity", "account") }
    val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)
    val latestIndex by rememberUpdatedState(selectedIndex)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestSelect by rememberUpdatedState(onSelect)
    val direction = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    // Two interruptible edge responses describe ONE shape. The faster edge leads,
    // while its slightly slower partner gives the capsule a restrained gel-like tail.
    // Animation and preview values are read only in drawBehind.
    val leading = remember { Animatable(selectedIndex.toFloat()) }
    val trailing = remember { Animatable(selectedIndex.toFloat()) }
    var preview by remember { mutableFloatStateOf(Float.NaN) }
    var previewTail by remember { mutableFloatStateOf(Float.NaN) }
    var settleRequest by remember { mutableIntStateOf(0) }
    var gestureGeneration by remember { mutableIntStateOf(0) }
    LaunchedEffect(reducedMotion, enabled) {
        if (!enabled) { gestureGeneration++; preview = Float.NaN; previewTail = Float.NaN }
        snapshotFlow { latestIndex to settleRequest }.collect { (target, _) ->
            if (reducedMotion || !enabled) {
                leading.snapTo(target.toFloat())
                trailing.snapTo(target.toFloat())
            } else {
                // Retarget an already-running Animatable, preserving its current
                // position and velocity; do not restart a keyed animation job.
                launch { leading.animateTo(target.toFloat(), spring(dampingRatio = .88f, stiffness = 650f)) }
                launch { trailing.animateTo(target.toFloat(), spring(dampingRatio = .92f, stiffness = 380f)) }
            }
        }
    }
    fun finishPreview(commit: Int?, expectedSelection: Int, generation: Int, finalPosition: Float, finalTail: Float) {
        scope.launch {
            // Seed the spring from the exact finger position instead of jumping
            // back to the previous tab when a drag ends or is cancelled.
            if (generation != gestureGeneration) return@launch
            leading.snapTo(finalPosition)
            if (generation != gestureGeneration) return@launch
            trailing.snapTo(finalTail)
            if (generation != gestureGeneration) return@launch
            preview = Float.NaN
            previewTail = Float.NaN
            if (latestEnabled && latestIndex == expectedSelection && commit != null && commit != latestIndex) latestSelect(tabs[commit])
            settleRequest++
        }
    }
    BoxWithConstraints(
        modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val horizontalGap = if (maxWidth < 360.dp) 8.dp else 14.dp
        Box(Modifier.fillMaxWidth().padding(start = horizontalGap, end = horizontalGap, top = 8.dp, bottom = dockBottomGap),
            contentAlignment = Alignment.BottomCenter) {
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = colors.surfaceContainer.copy(alpha = .97f),
                contentColor = colors.onSurface,
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .55f)),
                modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().testTag("floating_bottom_navigation"),
            ) {
                Box(
                    Modifier.background(Brush.verticalGradient(listOf(colors.onSurface.copy(alpha = .035f), Color.Transparent)))
                        .padding(4.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("floating_navigation_track")
                            .pointerInput(enabled, direction, reducedMotion) {
                                if (!enabled) return@pointerInput
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    val originalSelection = latestIndex
                                    var claimed = false
                                    var gestureToken = 0
                                    var ended = false
                                    var position = leading.value
                                    var tail = trailing.value
                                    try {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!latestEnabled || latestIndex != originalSelection || event.changes.count { it.pressed } > 1) {
                                                event.changes.forEach { it.consume() }
                                                break
                                            }
                                            if (change.isConsumed) break
                                            val delta = change.position - down.position
                                            if (!claimed) {
                                                if (abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) >= abs(delta.x)) {
                                                    // Reject the gesture as navigation AND cancel
                                                    // the child click, so a vertical drag cannot
                                                    // reselect the current tab and reset its page.
                                                    change.consume()
                                                    break
                                                }
                                                if (abs(delta.x) > viewConfiguration.touchSlop && abs(delta.x) > abs(delta.y) * 1.2f) {
                                                    claimed = true
                                                    gestureGeneration++
                                                    gestureToken = gestureGeneration
                                                    scope.launch {
                                                        if (gestureToken != gestureGeneration) return@launch
                                                        leading.stop()
                                                        if (gestureToken == gestureGeneration) trailing.stop()
                                                    }
                                                }
                                            }
                                            if (claimed) {
                                                change.consume()
                                                val physical = (change.position.x / (size.width.toFloat() / tabs.size) - .5f).coerceIn(0f, tabs.lastIndex.toFloat())
                                                val next = if (direction == LayoutDirection.Rtl) tabs.lastIndex - physical else physical
                                                // Finger tracking is direct; only a bounded trailing edge lags.
                                                tail = if (reducedMotion) next else (tail + (next - tail) * .55f).coerceIn(next - .18f, next + .18f)
                                                position = next
                                                preview = position
                                                previewTail = tail
                                                if (change.position.y < -size.height * .5f || change.position.y > size.height * 1.5f) break
                                                if (!change.pressed) {
                                                    ended = true
                                                    finishPreview(position.roundToInt().coerceIn(0, tabs.lastIndex), originalSelection, gestureToken, position, tail)
                                                    break
                                                }
                                            } else if (!change.pressed) break
                                        }
                                    } finally {
                                        if (claimed && !ended) finishPreview(null, originalSelection, gestureToken, position, tail)
                                    }
                                }
                            }.drawBehind {
                                // The hit track intentionally excludes the 6dp
                                // safe-area footroom. Let decoration include it
                                // so the selected pill has the same 4dp frame
                                // above and below, without moving touch targets.
                                val indicatorHeight = size.height + 6.dp.toPx()
                                val slotWidth = size.width / tabs.size
                                val first = if (preview.isNaN()) leading.value else preview
                                val second = if (previewTail.isNaN()) trailing.value else previewTail
                                val center = first.coerceIn(0f, tabs.lastIndex.toFloat())
                                val lag = if (reducedMotion) center else second.coerceIn(center - .18f, center + .18f).coerceIn(0f, tabs.lastIndex.toFloat())
                                val physicalCenter = if (layoutDirection == LayoutDirection.Rtl) tabs.lastIndex - center else center
                                val physicalTail = if (layoutDirection == LayoutDirection.Rtl) tabs.lastIndex - lag else lag
                                val left = max(0f, min(physicalCenter, physicalTail) * slotWidth)
                                val right = min(size.width, (max(physicalCenter, physicalTail) + 1f) * slotWidth)
                                val squeeze = if (reducedMotion) 0f else abs(center - lag) / .18f * .025f * indicatorHeight
                                drawRoundRect(
                                    color = colors.primaryContainer,
                                    topLeft = Offset(left, squeeze),
                                    size = Size(right - left, indicatorHeight - squeeze * 2f),
                                    cornerRadius = CornerRadius(27.dp.toPx()),
                                )
                            }.selectableGroup(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tabs.forEach { tab ->
                            val selected = selectedTab == tab
                            val label = tab.replaceFirstChar(Char::uppercase)
                            val foreground = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant
                            Column(
                                Modifier.weight(1f)
                                    .selectable(selected, interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Tab, onClick = { onSelect(tab) })
                                    .heightIn(min = 48.dp).padding(horizontal = 2.dp, vertical = 3.dp)
                                    .testTag("nav_$tab").semantics { contentDescription = label },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                            ) {
                                Icon(when (tab) {
                                    "groups" -> Icons.Default.Groups
                                    "friends" -> Icons.Default.Person
                                    "activity" -> Icons.Default.NotificationsNone
                                    else -> Icons.Default.AccountCircle
                                }, contentDescription = null, tint = foreground, modifier = Modifier.size(23.dp).offset(y = 3.dp))
                                Text(label, color = foreground, style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.offset(y = 3.dp).testTag("nav_label_$tab"))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Credit only the noninteractive 4dp frame + 6dp footroom, never the tab target. */
internal fun floatingNavigationBottomGap(bottomInset: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    maxOf(4.dp, bottomInset - 10.dp)
