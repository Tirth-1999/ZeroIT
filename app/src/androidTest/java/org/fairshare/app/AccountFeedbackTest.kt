package org.fairshare.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fairshare.app.data.LocalRepository
import org.fairshare.app.ui.FairshareApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountFeedbackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedNameConfirmsAndClearsFocusWithoutChangingLedger() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = "account-feedback-${System.nanoTime()}.db"
        val repository = LocalRepository(context, db)
        runBlocking { repository.setProfile("Tooth") }
        val before = repository.state.value
        compose.setContent { FairshareApp(repository) }
        compose.onNodeWithTag("nav_account").performClick()
        compose.onNodeWithTag("settings_save").assertIsNotEnabled()
        compose.onNodeWithTag("settings_name").performTextReplacement("TIRTH Shah")
        compose.onNodeWithTag("settings_name").assertIsFocused()
        compose.onNodeWithTag("settings_save").performScrollTo().performClick()
        compose.waitUntil(5000) { repository.state.value.profileName == "TIRTH Shah" }
        compose.onNodeWithTag("settings_name_saved").assertTextEquals("Name saved")
        compose.onNodeWithTag("settings_name").assertIsNotFocused()
        compose.onNodeWithTag("settings_save").assertIsNotEnabled()
        assertEquals(before.expenses, repository.state.value.expenses)
        assertEquals(before.settlements, repository.state.value.settlements)
        compose.onNodeWithTag("settings_name").performTextReplacement("Next name")
        compose.onNodeWithTag("settings_name_saved").assertDoesNotExist()
        repository.close()
        context.deleteDatabase(db)
    }
}
