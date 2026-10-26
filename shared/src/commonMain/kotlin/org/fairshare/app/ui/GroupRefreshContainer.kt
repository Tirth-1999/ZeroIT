package org.fairshare.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefreshIndicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.fairshare.app.platform.rememberReducedMotion

/** Wrap a vertically scrollable body. Refresh reloads local storage; it never implies cloud sync. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupRefreshContainer(
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = "group",
    successMessage: String = "Saved group data reloaded",
    content: @Composable () -> Unit,
) {
    var refreshing by remember { mutableStateOf(false) }
    var refreshFailed by remember { mutableStateOf(false) }
    val latestRefresh by rememberUpdatedState(onRefresh)
    val scope = rememberCoroutineScope()
    val feedback = remember { SnackbarHostState() }
    val reducedMotion = rememberReducedMotion()
    val materialState = rememberPullToRefreshState()
    // The drag still follows the finger. Only the automatic settle animation is removed.
    val pullState = remember(materialState, reducedMotion) {
        if (!reducedMotion) materialState else object : PullToRefreshState by materialState {
            override suspend fun animateToThreshold() = materialState.snapTo(1f)
            override suspend fun animateToHidden() = materialState.snapTo(0f)
        }
    }
    fun refresh(): Boolean {
        if (refreshing) return false
        refreshing = true
        refreshFailed = false
        feedback.currentSnackbarData?.dismiss()
        scope.launch {
            var failed = false
            val message = try {
                withTimeout(10_000) { latestRefresh() }
                // Brief acknowledgement makes a near-instant disk reload perceptible.
                if (!reducedMotion) delay(350)
                successMessage
            } catch (_: TimeoutCancellationException) {
                failed = true
                "Could not reload saved data."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
                "Could not reload saved data."
            } finally {
                refreshing = false
            }
            refreshFailed = failed
            // Recovery is screen state, not the result of a transient snackbar's
            // suspended continuation. Keep its action directly bound to refresh().
            if (!failed) feedback.showSnackbar(message, duration = SnackbarDuration.Short)
        }
        return true
    }
    // Use Material's standard drag threshold and resistance. Extra dead zones
    // made the gesture unnecessarily difficult and disconnected from the finger.
    Box(modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().pullToRefresh(
                isRefreshing = refreshing,
                onRefresh = { refresh() },
                state = pullState,
            ).testTag("${tagPrefix}_refresh_container").semantics {
                customActions = listOf(CustomAccessibilityAction("Reload saved data") { refresh() })
            },
        ) {
            content()
            val indicator = Modifier.align(Alignment.TopCenter)
                .testTag("${tagPrefix}_refresh_indicator").semantics {
                    stateDescription = if (refreshing) "Reloading saved data" else if (pullState.distanceFraction >= 1f) "Release to refresh" else "Pull further to refresh"
                    liveRegion = LiveRegionMode.Polite
                }
            if (reducedMotion) {
                Surface(
                    modifier = indicator.pullToRefreshIndicator(pullState, refreshing).size(40.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            } else {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = refreshing,
                    modifier = indicator,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        val feedbackModifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 88.dp).testTag("${tagPrefix}_refresh_feedback")
        if (refreshFailed) {
            Snackbar(
                modifier = feedbackModifier.semantics { liveRegion = LiveRegionMode.Polite },
                action = {
                    TextButton(
                        onClick = { refresh() },
                        modifier = Modifier.testTag("${tagPrefix}_refresh_retry"),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary),
                    ) { Text("Retry") }
                },
            ) { Text("Could not reload saved data.") }
        } else {
            SnackbarHost(feedback, feedbackModifier)
        }
    }
}
