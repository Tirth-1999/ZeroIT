package org.fairshare.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.fairshare.app.data.Repository
import org.fairshare.app.platform.rememberReducedMotion

/** Shared cold-start branding and loading; unreadable ledgers are preserved for retry. */
@Composable
fun FairshareRoot(repository: Repository, onBackgroundChanged: (Color, String) -> Unit = { _, _ -> }) {
    var ready by remember(repository) { mutableStateOf(false) }
    var error by remember(repository) { mutableStateOf<String?>(null) }
    var attempt by remember(repository) { mutableIntStateOf(0) }
    val reducedMotion = rememberReducedMotion()
    val entrance = remember(repository) { Animatable(0f) }
    var introComplete by remember(repository) { mutableStateOf(false) }

    // Animate only while loading; ready data never waits for branding to finish.
    LaunchedEffect(repository, reducedMotion) {
        if (introComplete) return@LaunchedEffect
        if (reducedMotion) entrance.snapTo(1f)
        else {
            entrance.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        }
        introComplete = true
    }
    LaunchedEffect(repository, attempt) {
        error = null
        try {
            repository.load()
            ready = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Your stored data has been preserved. Please retry."
        }
    }
    if (ready) FairshareApp(repository, onBackgroundChanged)
    else MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF5F5EF)) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).testTag("fairshare_launch"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ZeroitMark(Modifier.size(104.dp).testTag("fairshare_launch_logo"))
                Spacer(Modifier.height(20.dp))
                ZeroitWordmark(modifier = Modifier.graphicsLayer {
                    alpha = entrance.value
                    scaleX = 0.88f + 0.12f * entrance.value
                    scaleY = scaleX
                })
                if (error == null) {
                    Spacer(Modifier.height(16.dp))
                    Text("Opening your ledger…", style = MaterialTheme.typography.bodyMedium)
                    if (introComplete && !reducedMotion) {
                        Spacer(Modifier.height(16.dp))
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                } else {
                    Spacer(Modifier.height(24.dp))
                    Text("Your ledger could not be opened.", style = MaterialTheme.typography.titleLarge)
                    Text(error.orEmpty(), Modifier.padding(vertical = 16.dp))
                    Button(onClick = { attempt++ }) { Text("Try again") }
                }
            }
        }
    }
}
