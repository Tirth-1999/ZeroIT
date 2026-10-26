package org.fairshare.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.runtime.SideEffect
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
class TabSessionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchingTabsRestoresGroupTotalsScrollAndIndependentFriendsSearch() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = "tab-session-${System.nanoTime()}.db"
        val repository = LocalRepository(context, database)
        try {
            var groupId = ""
            runBlocking {
                repository.setProfile("Taylor")
                groupId = repository.createGroup("Trip across currencies", "USD", listOf("Alice"))
                listOf("USD", "EUR", "GBP").forEachIndexed { index, currency ->
                    repository.saveExpense(Expense("trip-$index", groupId, "Trip expense $index", 10000,
                        SELF_ID, mapOf(SELF_ID to 5000L, repository.state.value.members.first { it.id != SELF_ID }.id to 5000L), currency = currency))
                }
            }
            var keyboard: SoftwareKeyboardController? = null
            compose.setContent {
                val current = LocalSoftwareKeyboardController.current
                SideEffect { keyboard = current }
                FairshareApp(repository)
            }
            compose.onNodeWithTag("group_$groupId").performScrollTo().performClick()
            compose.onNodeWithTag("group_tab_totals").performScrollTo().performClick()
            compose.onNodeWithTag("group_expense_list").performTouchInput { swipeUp() }
            compose.waitForIdle()
            val position = scrollPosition()
            assertTrue("Totals must actually be scrolled before switching tabs", position > 0f)
            compose.onNodeWithTag("nav_friends").performClick()
            compose.onNodeWithContentDescription("Search friends").performClick()
            compose.onNodeWithTag("friend_search").performTextInput("Alice")
            compose.runOnIdle { keyboard?.hide() }
            compose.onNodeWithTag("nav_groups").performClick()
            compose.onNodeWithTag("group_title").assertTextEquals("Trip across currencies")
            compose.onNodeWithTag("group_tab_totals").assertIsSelected()
            assertEquals(position, scrollPosition(), 1f)
            compose.onNodeWithTag("nav_friends").performClick()
            compose.onNodeWithTag("friend_search").assertTextContains("Alice")
            compose.onNodeWithTag("nav_activity").performClick()
            compose.onNodeWithTag("nav_groups").performClick()
            compose.onNodeWithTag("group_tab_totals").assertIsSelected()
            assertEquals(position, scrollPosition(), 1f)
            compose.onNodeWithTag("nav_groups").performClick()
            compose.onNodeWithTag("groups_screen").assertExists()
        } finally {
            repository.close()
            context.deleteDatabase(database)
        }
    }

    private fun scrollPosition(): Float = compose.onNodeWithTag("group_expense_list")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
}
