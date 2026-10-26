package org.fairshare.app

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.uikit.OnFocusBehavior
import org.fairshare.app.data.createIosRepository
import org.fairshare.app.ui.FairshareRoot

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun MainViewController(databaseName: String = "fairshare.db", onBackgroundChanged: (Int, String) -> Unit = { _, _ -> }) = ComposeUIViewController(configure = {
    // Forms already resize with imePadding; a second native pan moves hit targets.
    onFocusBehavior = OnFocusBehavior.DoNothing
    // The shared edge host owns the interactive curve and exact touch coordinates.
    // UIKit's parallel edge recognizer cancels touches before it can track them.
    enableBackGesture = false
}) {
    val repository = remember(databaseName) { createIosRepository(databaseName) }
    FairshareRoot(repository) { color, mode -> onBackgroundChanged(color.toArgb(), mode) }
}
