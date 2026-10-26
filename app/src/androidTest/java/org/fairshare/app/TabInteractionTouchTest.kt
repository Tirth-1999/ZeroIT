package org.fairshare.app

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fairshare.app.data.LocalRepository
import org.fairshare.app.model.Expense
import org.fairshare.app.model.SELF_ID
import org.fairshare.app.ui.FairshareApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Touch dispatch regression. Real-time paced frames prevent a passing test from
 * relying on the Compose test clock skipping the entire transition instantly.
 * Screenshots and readiness bounds are not a rendered FPS/jank measurement. */
@RunWith(AndroidJUnit4::class)
class TabInteractionTouchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun touchRoundtripRestoresTotalsAndRapidTouchesEndAtLastDestination() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = "touch-tabs-${System.nanoTime()}.db"
        val repository = LocalRepository(context, database)
        try {
            val groupId = runBlocking {
                repository.setProfile("Taylor")
                val id = repository.createGroup("Touch navigation", "USD", listOf("Alice"))
                val other = repository.state.value.members.first { it.id != SELF_ID }.id
                listOf("USD", "EUR", "GBP").forEachIndexed { index, currency ->
                    repository.saveExpense(Expense("touch-$index", id, "Trip expense $index", 10000,
                        SELF_ID, mapOf(SELF_ID to 5000L, other to 5000L), currency = currency))
                }
                id
            }
            compose.setContent { FairshareApp(repository) }
            // Repository writes above create only the isolated fixture. Every
            // navigation assertion below starts with a pointer click/swipe.
            compose.onNodeWithTag("group_$groupId").performTouchInput { click() }
            compose.onNodeWithTag("group_tab_totals").performScrollTo().performTouchInput { click() }
            compose.onNodeWithTag("group_expense_list").performTouchInput { swipeUp() }
            compose.waitForIdle()
            val position = scrollPosition()
            assertTrue("Totals fixture must scroll before switching", position > 0f)
            compose.mainClock.autoAdvance = false

            touchTabAndCheck("friends", "friends_screen")
            touchTabAndCheck("activity", "activity_screen")
            touchTabAndCheck("account", "settings_name")
            touchTabAndCheck("groups", "group_title")
            compose.onNodeWithTag("group_title").assertTextEquals("Touch navigation")
            compose.onNodeWithTag("group_tab_totals").assertIsSelected()
            assertEquals("Group scroll survives a real touch roundtrip", position, scrollPosition(), 1f)
            compose.captureEvidence("touch-tabs-restored-totals")

            // One touch stream with quick repeated taps: do not wait for each
            // destination or manufacture semantic click actions between events.
            val centers = listOf("friends", "activity", "account", "groups").map { tab ->
                compose.onNodeWithTag("nav_$tab").fetchSemanticsNode().boundsInRoot.center
            }
            val rootOrigin = compose.onRoot().fetchSemanticsNode().boundsInRoot.topLeft
            compose.onRoot().performTouchInput {
                centers.forEach { point ->
                    click(Offset(point.x - rootOrigin.x, point.y - rootOrigin.y))
                    advanceEventTime(60)
                }
            }
            settleAtWallClockPace()
            compose.onNodeWithTag("nav_groups").assertIsSelected()
            compose.onNodeWithTag("group_title").assertTextEquals("Touch navigation")
            compose.onNodeWithTag("group_tab_totals").assertIsSelected()
            assertEquals(position, scrollPosition(), 1f)
            compose.captureEvidence("touch-tabs-consecutive-final-destination")
        } finally {
            compose.mainClock.autoAdvance = true
            repository.close()
            context.deleteDatabase(database)
        }
    }

    private fun touchTabAndCheck(tab: String, destination: String) {
        val dispatchStarted = SystemClock.elapsedRealtime()
        compose.onNodeWithTag("nav_$tab").performTouchInput {
            down(center); advanceEventTime(1); up()
        }
        val dispatched = SystemClock.elapsedRealtime()
        // Test event dispatch includes host synchronization and is reported
        // separately. The readiness deadline starts after the touch is delivered.
        settleAtWallClockPace(tab, destination)
        val ready = SystemClock.elapsedRealtime()
        Log.i("FairshareTouchTest", "$tab dispatch=${dispatched - dispatchStarted}ms readiness=${ready - dispatched}ms total=${ready - dispatchStarted}ms")
        compose.onNodeWithTag("nav_$tab").assertIsSelected()
        compose.onNodeWithTag(destination).assertExists()
        assertTrue("$tab post-dispatch touch readiness took ${ready - dispatched}ms (limit 2000ms)", ready - dispatched <= 2000)
    }

    private fun settleAtWallClockPace(tab: String? = null, destination: String? = null) {
        val started = SystemClock.elapsedRealtime()
        val startClock = compose.mainClock.currentTime
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - started
            // Never advance simulated animation faster than real elapsed time.
            // A fixed loop of 24 synchronized calls was measuring test-runner
            // overhead, rather than 384 ms of interaction.
            val advanced = compose.mainClock.currentTime - startClock
            if (elapsed > advanced) compose.mainClock.advanceTimeBy(elapsed - advanced, ignoreFrameDuration = true)
            val animationElapsed = compose.mainClock.currentTime - startClock
            val selected = tab == null || compose.onAllNodesWithTag("nav_$tab").fetchSemanticsNodes()
                .any { it.config.getOrNull(SemanticsProperties.Selected) == true }
            val visible = destination == null || compose.onAllNodesWithTag(destination).fetchSemanticsNodes().isNotEmpty()
            if (animationElapsed >= 240 && selected && visible) return
            assertTrue("Touch destination did not become ready within 2000ms (tab=$tab, destination=$destination)",
                SystemClock.elapsedRealtime() - started < 2000)
            SystemClock.sleep(8)
        }
    }

    private fun scrollPosition(): Float = compose.onNodeWithTag("group_expense_list")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
}
