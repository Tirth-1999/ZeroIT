package org.fairshare.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.fairshare.app.data.LocalRepository
import org.fairshare.app.data.Repository
import org.fairshare.app.ui.FairshareRoot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class LaunchParityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun approvedBrandingIsVisibleInLightAndDarkOnboarding() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "brand-${System.nanoTime()}.db"
        val repository = LocalRepository(context, name)
        val mounted = mutableStateOf(true)
        try {
            runBlocking { repository.load(); repository.setTheme("LIGHT") }
            compose.setContent { if (mounted.value) FairshareRoot(repository) }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("welcome_continue").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("ZeroIT").assertIsDisplayed()
            compose.onNodeWithContentDescription("ZeroIT logo").assertIsDisplayed()
            compose.captureEvidence("zeroit-onboarding-light")
            runBlocking { repository.setTheme("DARK") }
            compose.waitForIdle()
            compose.onNodeWithText("ZeroIT").assertIsDisplayed()
            compose.onNodeWithContentDescription("ZeroIT logo").assertIsDisplayed()
            compose.captureEvidence("zeroit-onboarding-dark")
        } finally {
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            repository.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun readyLedgerDoesNotWaitForTheStartupAnimation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "launch-ready-${System.nanoTime()}.db"
        val repository = LocalRepository(context, name)
        val mounted = mutableStateOf(true)
        try {
            runBlocking { repository.setProfile("Taylor") }
            val saved = repository.state.value
            // Data is already loaded; keep the animation clock paused to prove
            // a ready ledger can open without waiting for a branding duration.
            val loaded = object : Repository by repository {
                override suspend fun load() = Unit
            }
            compose.mainClock.autoAdvance = false
            compose.setContent { if (mounted.value) FairshareRoot(loaded) }
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            compose.onNodeWithTag("groups_screen").assertIsDisplayed()
            compose.onNodeWithTag("fairshare_launch").assertDoesNotExist()
            assertEquals(saved, repository.state.value)
        } finally {
            compose.mainClock.autoAdvance = true
            try {
                // Dispose collectors, loading work and observers before closing their data source.
                compose.runOnIdle { mounted.value = false }
                compose.waitForIdle()
            } finally {
                repository.close()
                context.deleteDatabase(name)
            }
        }
    }

    @Test fun sharedLaunchLogoSurvivesLoadFailureAndRetryPreservesLedger() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "launch-parity-${System.nanoTime()}.db"
        val repository = LocalRepository(context, name)
        val mounted = mutableStateOf(true)
        val resumeLoad = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        try {
            runBlocking {
                repository.setProfile("Taylor")
                repository.createGroup("Preserved ledger", "EUR", listOf("Alice"))
            }
            val saved = repository.state.value
            val observed = object : Repository by repository {
                override suspend fun load() {
                    if (attempts.incrementAndGet() == 1) throw IllegalStateException("Temporary test read failure")
                    resumeLoad.await()
                    repository.load()
                }
            }
            compose.setContent { if (mounted.value) FairshareRoot(observed) }
            compose.onNodeWithText("Try again").assertIsDisplayed().performClick()
            compose.onNodeWithTag("fairshare_launch_logo").assertIsDisplayed()
            compose.onNodeWithContentDescription("ZeroIT logo").assertExists()
            compose.captureEvidence("zeroit-launch-retry")
            assertEquals(saved, repository.state.value)
            resumeLoad.complete(Unit)
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("groups_screen").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("fairshare_launch").assertDoesNotExist()
            assertEquals(2, attempts.get())
            assertEquals(saved, repository.state.value)
        } finally {
            try {
                compose.runOnIdle { mounted.value = false }
                compose.waitForIdle()
            } finally {
                resumeLoad.complete(Unit)
                repository.close()
                context.deleteDatabase(name)
            }
        }
    }
}
