package org.fairshare.app

import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fairshare.app.data.LocalRepository
import org.fairshare.app.data.Repository
import org.fairshare.app.model.Expense
import org.fairshare.app.model.SELF_ID
import org.fairshare.app.ui.FairshareApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class HomeRefreshTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homePullReloadsNewSavedExpenseAndRetryRecoversWithoutWriting() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "home-refresh-${System.nanoTime()}.db"
        val reader = LocalRepository(context, name)
        val writer = LocalRepository(context, name)
        val fail = AtomicBoolean(false)
        val calls = AtomicInteger()
        val observed = object : Repository by reader {
            override suspend fun refresh() {
                calls.incrementAndGet()
                if (fail.get()) throw IllegalStateException("Test read failure")
                reader.refresh()
            }
        }
        try {
            runBlocking {
                reader.setProfile("Taylor")
                reader.createGroup("Home reload", "USD", listOf("Alice", "Bob"))
            }
            compose.setContent { FairshareApp(observed) }
            compose.onNodeWithTag("groups_screen").assertExists()
            // Standard Material still ignores taps and short pulls below its threshold.
            for (distance in listOf(12, 60)) {
                val dragPixels = with(compose.density) { distance.dp.toPx() }
                compose.onNodeWithTag("groups_screen").performTouchInput {
                    swipeDown(startY = height * .15f, endY = height * .15f + dragPixels, durationMillis = 600)
                }
                compose.runOnIdle { assertEquals(0, calls.get()) }
            }
            compose.onNodeWithTag("home_refresh_container").performTouchInput { click() }
            compose.runOnIdle { assertEquals(0, calls.get()) }
            // Simulate a newly saved expense from another repository instance.
            runBlocking {
                writer.load()
                val group = writer.state.value.groups.single()
                writer.saveExpense(Expense("new-bill", group.id, "Fresh groceries", 6000, SELF_ID, group.memberIds.associateWith { 2000L }))
            }
            assertEquals(0, reader.state.value.expenses.size)
            // A normal 210 dp pull crosses Material’s standard threshold without
            // requiring the previous ~392 dp drag, and still waits for release.
            val normalPullPixels = with(compose.density) { 210.dp.toPx() }
            compose.onNodeWithTag("groups_screen").performTouchInput {
                down(Offset(centerX, height * .03f))
                moveTo(Offset(centerX, height * .03f + normalPullPixels), delayMillis = 600)
            }
            compose.runOnIdle { assertEquals(0, calls.get()) }
            compose.onNodeWithTag("groups_screen").performTouchInput { up() }
            waitFeedback("Saved expenses and balances reloaded")
            assertEquals(writer.state.value, reader.state.value)
            compose.onAllNodesWithText("$40.00").assertCountEquals(2)
            val beforeFailure = reader.state.value
            fail.set(true)
            pull()
            waitFeedback("Could not reload saved data.")
            assertEquals(beforeFailure, reader.state.value)
            fail.set(false)
            compose.onNodeWithTag("home_refresh_retry").performClick()
            compose.waitUntil(5000) { calls.get() == 3 }
            waitFeedback("Saved expenses and balances reloaded")
            assertEquals(beforeFailure, reader.state.value)
            compose.onNodeWithTag("groups_screen").assertExists()
        } finally {
            reader.close()
            writer.close()
            context.deleteDatabase(name)
        }
    }

    private fun pull() {
        compose.onNodeWithTag("groups_screen").performTouchInput {
            swipeDown(startY = height * .03f, endY = height * .97f, durationMillis = 600)
        }
    }
    private fun waitFeedback(text: String) {
        compose.waitUntil(5000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}
