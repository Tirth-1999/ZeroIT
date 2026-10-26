package org.fairshare.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fairshare.app.data.LocalRepository
import org.fairshare.app.ui.FairshareApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpenseChooserKeyboardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun friendsExpenseChooserKeepsFocusWhenFirstCharacterChangesResults() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = "chooser-keyboard-${System.nanoTime()}.db"
        val repository = LocalRepository(context, database)
        try {
            runBlocking {
                repository.setProfile("Taylor")
                repository.createGroup("Alpha trip", "USD", emptyList())
                repository.createGroup("Zulu trip", "USD", emptyList())
            }
            val alpha = repository.state.value.groups.single { it.name == "Alpha trip" }
            val zulu = repository.state.value.groups.single { it.name == "Zulu trip" }
            compose.setContent { FairshareApp(repository) }
            compose.onNodeWithTag("nav_friends").performClick()
            compose.onNodeWithTag("global_add_expense").performClick()
            compose.onNodeWithTag("choose_group_${alpha.id}").assertExists()
            compose.onNodeWithTag("choose_group_${zulu.id}").assertExists()
            val search = compose.onNodeWithTag("expense_destination_search")
            search.performClick().assertIsFocused()
            search.performTextInput("a")
            search.assertIsFocused().assertTextContains("a")
            compose.onNodeWithTag("choose_group_${alpha.id}").assertExists()
            compose.onNodeWithTag("choose_group_${zulu.id}").assertDoesNotExist()
            // No retap between characters: a changing result list and newly visible
            // clear button must leave the same text-input session focused.
            search.performTextInput("l")
            search.assertIsFocused().assertTextContains("al")
            compose.onNodeWithTag("choose_group_${alpha.id}").assertExists()
            compose.onNodeWithTag("choose_group_${zulu.id}").assertDoesNotExist()
        } finally {
            repository.close()
            context.deleteDatabase(database)
        }
    }
}
