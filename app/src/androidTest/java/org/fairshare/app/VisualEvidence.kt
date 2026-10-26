package org.fairshare.app

import android.graphics.Bitmap
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Full-screen evidence from isolated acceptance databases, never the normal ledger. */
internal fun ComposeContentTestRule.captureEvidence(name: String) {
    waitForIdle()
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    // Android Gradle retrieves this runner-owned directory before uninstalling
    // the test app. Local/manual runners retain the app-private fallback.
    val outputPath = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
    val exported = outputPath?.takeIf { it.isNotBlank() }?.let { File(it, "ui-evidence") }
    val directory = exported?.takeIf { it.isDirectory || it.mkdirs() }
        ?: File(context.filesDir, "ui-evidence").apply { mkdirs() }
    // Capture the complete display, including popup/dialog windows with separate roots.
    val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) { "Could not capture the test display" }
    try {
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } finally { bitmap.recycle() }
}

internal fun ComposeContentTestRule.waitForCelebrationStart() {
    try {
        // The save coroutine may still be writing on IO after the click. Pump one
        // frame per wall-clock poll so recomposition proceeds without racing through
        // the entire acknowledgement before its first frame can be inspected.
        waitUntil(5_000) {
            val visible = onAllNodesWithTag("save_celebration").fetchSemanticsNodes().isNotEmpty()
            if (!visible && !mainClock.autoAdvance) mainClock.advanceTimeByFrame()
            visible
        }
    }
    catch (failure: Throwable) { throw AssertionError("Celebration did not appear. Current UI:\n${onRoot(useUnmergedTree = true).printToString()}", failure) }
}

/** Read the visible text leaf, rather than the clickable overlay's merged label. */
internal fun ComposeContentTestRule.assertCelebrationMessageDisplayed(message: String, evidenceName: String) {
    captureEvidence(evidenceName)
    try {
        onNode(hasText(message) and hasAnyAncestor(hasTestTag("save_celebration")), useUnmergedTree = true)
            .assertIsDisplayed()
    } catch (failure: Throwable) {
        val overlay = onAllNodesWithTag("save_celebration", useUnmergedTree = true).fetchSemanticsNodes()
        throw AssertionError("Acknowledgement text is not visible: $message. " +
            "Overlay bounds=${overlay.map { it.boundsInRoot }}. " +
            "Root bounds=${onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot}\n" +
            onRoot(useUnmergedTree = true).printToString(), failure)
    }
}

internal fun ComposeContentTestRule.waitForCelebrationEnd() {
    waitUntil(5_000) { onAllNodesWithTag("save_celebration").fetchSemanticsNodes().isEmpty() }
}

/** Lazy rows are materialized by scrolling the collection; eager pages retain their normal path. */
internal fun ComposeContentTestRule.scrollLedgerItemToView(tag: String) {
    if (onAllNodesWithTag("group_expense_list").fetchSemanticsNodes().isNotEmpty())
        onNodeWithTag("group_expense_list").performScrollToNode(hasTestTag(tag))
    else if (onAllNodesWithTag("activity_screen").fetchSemanticsNodes().isNotEmpty())
        onNodeWithTag("activity_screen").performScrollToNode(hasTestTag(tag))
    else onNodeWithTag(tag).performScrollTo()
}
