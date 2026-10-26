package org.fairshare.app

import androidx.compose.ui.semantics.SemanticsProperties
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

@RunWith(AndroidJUnit4::class)
class SuccessfulWriteScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deletingAnOldActivityReturnsTheWholeFeedToZeroWithoutLosingHistory() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = "write-scroll-activity-${System.nanoTime()}.db"
        val repository = LocalRepository(context, db)
        try {
            runBlocking {
                repository.setProfile("Taylor")
                val group = repository.createGroup("Long activity history", "USD", emptyList())
                repeat(30) { index ->
                    repository.saveExpense(Expense("history-$index", group, "Expense $index", 100L,
                        SELF_ID, mapOf(SELF_ID to 100L)))
                }
            }
            val before = repository.state.value
            val old = before.activity.first { it.expenseId == "history-0" }
            compose.setContent { FairshareApp(repository) }
            compose.onNodeWithTag("nav_activity").performClick()
            compose.scrollLedgerItemToView("activity_${old.id}")
            assertTrue(scrollPosition("activity_screen") > 0f)
            compose.onNodeWithTag("activity_actions_${old.id}").performClick()
            compose.onNodeWithTag("activity_delete_${old.id}").performClick()
            // Cancelling a mutation must preserve the user's place.
            compose.onNodeWithText("Cancel").performClick()
            assertTrue(scrollPosition("activity_screen") > 0f)
            compose.onNodeWithTag("activity_actions_${old.id}").performClick()
            compose.onNodeWithTag("activity_delete_${old.id}").performClick()
            compose.onNodeWithTag("activity_confirm_delete_${old.id}").performClick()
            compose.waitUntil(5000) { repository.state.value.expenses.first { it.id == "history-0" }.deleted }
            compose.waitForCelebrationEnd()
            assertEquals(0f, scrollPosition("activity_screen"), 0f)
            val newest = repository.state.value.activity.last()
            compose.onNodeWithTag("activity_${newest.id}").assertIsDisplayed()
            assertEquals(before.activity, repository.state.value.activity.take(before.activity.size))
        } finally { repository.close(); context.deleteDatabase(db) }
    }

    @Test fun creatingGroupThenReturningHomeStartsAtTopOfWholePage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = "write-scroll-group-${System.nanoTime()}.db"
        val repository = LocalRepository(context, db)
        try {
            runBlocking {
                repository.setProfile("Taylor")
                repeat(25) { repository.createGroup("Group %02d".format(it), "USD", emptyList()) }
            }
            val last = repository.state.value.groups.first()
            lateinit var focusManager: androidx.compose.ui.focus.FocusManager
            compose.setContent {
                focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                FairshareApp(repository)
            }
            compose.onNodeWithTag("group_${last.id}").performScrollTo()
            assertTrue(scrollPosition("groups_screen") > 0f)
            compose.onNodeWithTag("create_group").performClick()
            compose.onNodeWithTag("group_name").performTextInput("New group")
            // Finish text-field layout before dispatching the save touch. A
            // semantics scroll while the text field is invalidating its bounds
            // races SnapshotStateObserver in this Compose test runtime.
            compose.runOnUiThread { focusManager.clearFocus(force = true) }
            compose.onNodeWithTag("group_save").performTouchInput { click() }
            // Wait on immutable repository state, then inspect semantics only
            // after Compose is idle. Polling its tree here raced UI-thread
            // semantics invalidation in the pinned Compose test runtime.
            compose.waitUntil(5000) { repository.state.value.groups.any { it.name == "New group" } }
            compose.waitForIdle()
            compose.onNodeWithTag("group_title").assertTextEquals("New group")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag("groups_screen").assertExists()
            assertEquals(0f, scrollPosition("groups_screen"), 0f)
            val created = repository.state.value.groups.last()
            compose.onNodeWithTag("group_${created.id}").assertIsDisplayed()
            val next = repository.state.value.groups[repository.state.value.groups.lastIndex - 1]
            assertTrue(compose.onNodeWithTag("group_${created.id}").getUnclippedBoundsInRoot().top <
                compose.onNodeWithTag("group_${next.id}").getUnclippedBoundsInRoot().top)
        } finally { repository.close(); context.deleteDatabase(db) }
    }

    private fun scrollPosition(tag: String): Float = compose.onNodeWithTag(tag)
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
}
