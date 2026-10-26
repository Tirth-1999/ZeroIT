package org.fairshare.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fairshare.app.data.LocalRepository
import org.fairshare.app.domain.Ledger
import org.fairshare.app.model.*
import org.fairshare.app.ui.FairshareApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsPersistOnlyChosenGroupAndPreserveAccounting() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "group-settings-${System.nanoTime()}.db"
        val repository = LocalRepository(context, name)
        try {
            runBlocking {
                repository.setProfile("Taylor")
                val id = repository.createGroup("Weekend", "USD", listOf("Alice", "Bob"))
                val people = repository.state.value.groups.single().memberIds
                repository.saveExpense(Expense("usd", id, "Dinner", 3000, SELF_ID, people.associateWith { 1000L }))
                repository.saveExpense(Expense("eur", id, "Lunch", 1500, SELF_ID, people.associateWith { 500L }, currency = "EUR"))
                repository.createGroup("Household", "EUR", emptyList())
            }
            val before = repository.state.value
            val group = before.groups.first()
            compose.setContent { FairshareApp(repository) }
            compose.onNodeWithTag("group_${group.id}").performClick()
            compose.onNodeWithTag("group_tab_totals").performClick()
            compose.onNodeWithTag("group_settings").performClick()
            compose.onNodeWithTag("group_settings_screen").assertExists()
            compose.onNodeWithTag("group_theme_TRAVEL").performScrollTo().performClick()
            compose.waitUntil(5000) { repository.state.value.groups.first().theme == GroupTheme.TRAVEL }
            compose.onNodeWithTag("group_theme_TRAVEL").assertIsSelected()
            compose.onNodeWithTag("group_rename").performScrollTo().performClick()
            compose.onNodeWithTag("group_settings_name").performTextReplacement("")
            compose.onNodeWithTag("group_settings_save_name").assertIsNotEnabled()
            compose.onNodeWithTag("group_settings_name").performTextInput("Road trip")
            compose.onNodeWithTag("group_settings_save_name").performClick()
            compose.waitUntil(5000) { repository.state.value.groups.first().name == "Road trip" }
            compose.onNodeWithTag("group_settings_members").performScrollTo().performClick()
            compose.onNodeWithTag("members_title").assertTextEquals("3 members · Road trip")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag("group_settings_screen").assertExists()
            compose.onNodeWithTag("group_settings_currencies").performScrollTo().assertTextContains("EUR", substring = true)
            compose.onNodeWithTag("group_settings_currencies").assertTextContains("USD", substring = true)
            compose.onNodeWithTag("group_settings_balances").performScrollTo().performClick()
            compose.onNodeWithTag("group_tab_balances").assertIsSelected()
            compose.onNodeWithTag("group_settings").performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag("group_tab_balances").assertIsSelected()
            compose.onNodeWithTag("nav_groups").performClick()
            compose.onNodeWithContentDescription("Travel group icon").assertExists()
            val saved = repository.state.value
            assertEquals(before.groups.last(), saved.groups.last())
            assertEquals(before.expenses, saved.expenses)
            assertEquals(before.settlements, saved.settlements)
            assertEquals(before.themeMode, saved.themeMode)
            listOf("USD", "EUR").forEach { currency -> assertEquals(Ledger.balances(before, group.id, currency), Ledger.balances(saved, group.id, currency)) }
            runBlocking {
                repository.updateGroupDetails(group.id, "Road trip", GroupTheme.TRAVEL)
                assertEquals(saved, repository.state.value)
                try { repository.updateGroupDetails(group.id, "\n", GroupTheme.HOME); fail("Invalid name accepted") } catch (_: IllegalArgumentException) { }
                assertEquals(saved, repository.state.value)
            }
            repository.close()
            val reopened = LocalRepository(context, name)
            try {
                runBlocking { reopened.load() }
                assertEquals(saved, reopened.state.value)
            } finally { reopened.close() }
        } finally {
            repository.close()
            context.deleteDatabase(name)
        }
    }
}
