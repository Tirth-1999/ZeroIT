package org.fairshare.app

import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
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
class HomeNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tabsFriendsDirectExpensesActivityAndGroupContext() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "home-${System.nanoTime()}.db"
        val repository = LocalRepository(context, name)
        runBlocking {
            repository.setProfile("Taylor")
            val group = repository.createGroup("Weekend", "USD", listOf("Alice", "Bob"))
            val ids = repository.state.value.groups.single().memberIds
            repository.saveExpense(Expense("shared-dinner", group, "Group dinner", 3000, SELF_ID, ids.associateWith { 1000L }))
        }
        val originalGroup = repository.state.value.groups.single()
        val alice = repository.state.value.members.single { it.name == "Alice" }.id
        var keyboardController: SoftwareKeyboardController? = null
        compose.setContent {
            val currentKeyboardController = LocalSoftwareKeyboardController.current
            SideEffect { keyboardController = currentKeyboardController }
            FairshareApp(repository)
        }
        listOf("groups", "friends", "activity", "account").forEach { compose.onNodeWithTag("nav_$it").assertExists() }
        compose.onNodeWithTag("nav_groups").assertIsSelected()
        compose.onNodeWithTag("nav_friends").performClick()
        compose.onNodeWithTag("nav_friends").assertIsSelected()
        compose.onNodeWithTag("friend_$alice").assertExists()
        compose.onNodeWithContentDescription("Search friends").performClick()
        compose.onNodeWithTag("friend_search").performTextInput("Alice")
        compose.onNodeWithTag("friend_$alice").performScrollTo().performClick()
        compose.onNodeWithTag("friend_group_${originalGroup.id}").performScrollTo().performClick()
        compose.onNodeWithTag("group_title").assertTextEquals("Weekend")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("friend_screen").assertExists()
        compose.onNodeWithTag("nav_friends").performClick()
        compose.onNodeWithTag("add_friend").performClick()
        compose.onNodeWithTag("friend_name").performTextInput("Dana")
        compose.onNodeWithTag("friend_save").performClick()
        compose.waitUntil(5000) { repository.state.value.members.any { it.name == "Dana" } }
        val dana = repository.state.value.members.single { it.name == "Dana" }.id
        compose.onNodeWithTag("friend_add_expense").performScrollTo().performClick()
        compose.onNodeWithTag("nav_friends").assertDoesNotExist()
        compose.onNodeWithTag("expense_title").performTextInput("Coffee")
        compose.onNodeWithTag("expense_amount").performTextInput("12.00")
        chooseCurrency("USD")
        finishExpense()
        compose.waitUntil(5000) { repository.state.value.expenses.size == 2 }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Non-group expenses").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("group_title").assertTextEquals("Non-group expenses")
        compose.onNodeWithTag("nav_friends").performClick()
        compose.onNodeWithTag("friend_$dana").performScrollTo().performClick()
        compose.onNodeWithTag("friend_screen").assertExists()
        assertEquals(600L, Ledger.friendBalances(repository.state.value, dana)["USD"])
        val direct = repository.state.value.groups.single { it.isDirect }
        compose.onNodeWithTag("friend_settle_${direct.id}").performScrollTo().performClick()
        compose.onNodeWithTag("settlement_amount").performScrollTo().performTextReplacement("2.00")
        compose.onNodeWithTag("settlement_amount").assertTextContains("2.00")
        compose.onNodeWithTag("settlement_currency").assertTextContains("USD")
        // The IME changes the viewport outside Compose's animation clock. Close it
        // before scrolling, then wait for the Android inset transition to settle.
        compose.runOnIdle { keyboardController?.hide() }
        compose.waitForIdle()
        compose.onNodeWithTag("settlement_save").assertIsDisplayed()
        waitForStableVisibleBounds("settlement_save")
        compose.onNodeWithTag("settlement_save").assertIsDisplayed().assertIsEnabled().performClick()
        try {
            compose.waitUntil(5000) { repository.state.value.settlements.size == 1 }
        } catch (failure: Throwable) {
            throw AssertionError("Settlement did not save; state=${repository.state.value}\n${compose.onRoot(useUnmergedTree = true).printToString()}", failure)
        }
        assertEquals(400L, Ledger.friendBalances(repository.state.value, dana)["USD"])
        compose.onNodeWithTag("nav_groups").performClick()
        compose.onNodeWithTag("group_${originalGroup.id}").assertExists()
        compose.onNodeWithTag("group_${direct.id}").assertDoesNotExist()
        compose.onNodeWithTag("non_group_expenses").performScrollTo().performClick()
        compose.onNodeWithTag("group_title").assertTextEquals("Non-group expenses")
        compose.onNodeWithTag("group_expense_${repository.state.value.expenses.single { it.groupId == direct.id }.id}").assertExists()
        compose.onNodeWithTag("group_tab_balances").performClick()
        compose.onNodeWithTag("group_balance").assertTextEquals("$4.00")
        compose.onNodeWithTag("group_members").performClick()
        compose.onNodeWithTag("members_title").assertTextEquals("2 members · Non-group expenses")
        compose.onNodeWithTag("add_members").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("group_tab_balances").assertIsSelected()
        compose.onNodeWithTag("group_tab_expenses").performClick()
        compose.onNodeWithTag("add_expense").performClick()
        compose.onNodeWithTag("expense_title").performTextInput("Personal lunch")
        compose.onNodeWithTag("expense_amount").performTextInput("20.00")
        chooseCurrency("USD")
        finishExpense { compose.onNodeWithTag("participant_$dana").performScrollTo().performClick() }
        compose.waitUntil(5000) { repository.state.value.expenses.size == 3 }
        assertEquals(direct.id, repository.state.value.expenses.last().groupId)
        compose.onNodeWithTag("group_title").assertTextEquals("Non-group expenses")
        compose.onNodeWithTag("expense_edit").assertDoesNotExist()
        compose.onNodeWithTag("nav_groups").performClick()
        assertEquals(1, repository.state.value.groups.count { it.isDirect })
        compose.onNodeWithTag("home_add_menu").performClick()
        compose.onNodeWithTag("global_add_expense").performClick()
        compose.onNodeWithTag("choose_group_${originalGroup.id}").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("group_title").assertTextEquals("Weekend")
        compose.onNodeWithTag("nav_activity").performClick()
        compose.onNodeWithTag("activity_screen").assertExists()
        val activity = repository.state.value.activity.last()
        compose.onNodeWithTag("activity_${activity.id}").performScrollTo().performClick()
        compose.onNodeWithTag("group_title").assertTextEquals("Non-group expenses")
        compose.onNodeWithTag("nav_activity").assertIsSelected()
        compose.onNodeWithTag("nav_account").performClick()
        compose.onNodeWithTag("theme_dark").performScrollTo().performClick()
        compose.waitUntil(5000) { repository.state.value.themeMode == "DARK" }
        compose.onNodeWithTag("nav_groups").performClick()
        compose.onNodeWithTag("groups_screen").assertExists()
        val saved = repository.state.value
        repository.close()
        val reopened = LocalRepository(context, name)
        runBlocking { reopened.load() }
        assertEquals(saved, reopened.state.value)
        reopened.close()
        context.deleteDatabase(name)
    }
    @Test fun expenseDestinationSearchFiltersClearsAndOpensCorrectContext() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "destination-search-${System.nanoTime()}.db"
        val repository = LocalRepository(context, name)
        runBlocking {
            repository.setProfile("Taylor")
            repository.createGroup("Weekend trip", "USD", listOf("Alice"))
            repository.createGroup("Household", "EUR", listOf("Bob"))
        }
        val weekend = repository.state.value.groups.single { it.name == "Weekend trip" }
        val house = repository.state.value.groups.single { it.name == "Household" }
        val alice = repository.state.value.members.single { it.name == "Alice" }
        compose.setContent { FairshareApp(repository) }
        compose.onNodeWithTag("home_add_menu").performClick()
        compose.onNodeWithTag("global_add_expense").performClick()
        compose.onNodeWithTag("expense_destination_search").performTextInput("  wEeK  ")
        compose.onNodeWithTag("choose_group_${weekend.id}").assertIsDisplayed()
        compose.onNodeWithTag("choose_group_${house.id}").assertDoesNotExist()
        compose.onNodeWithTag("choose_friend_${alice.id}").assertDoesNotExist()
        compose.onNodeWithTag("expense_destination_search").performTextReplacement("missing destination")
        compose.onNodeWithTag("expense_destination_empty").assertIsDisplayed()
        compose.onNodeWithTag("expense_destination_clear").performClick()
        compose.onNodeWithTag("choose_group_${house.id}").assertExists()
        compose.onNodeWithTag("expense_destination_search").performTextInput("week")
        compose.onNodeWithTag("choose_group_${weekend.id}").performClick()
        compose.onNodeWithTag("expense_title").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("group_title").assertTextEquals("Weekend trip")
        compose.onNodeWithTag("nav_groups").performClick()
        compose.onNodeWithTag("home_add_menu").performClick()
        compose.onNodeWithTag("global_add_expense").performClick()
        compose.onNodeWithTag("expense_destination_search").assertTextContains("Search groups or friends")
        compose.onNodeWithTag("choose_group_${house.id}").assertExists()
        compose.onNodeWithTag("expense_destination_search").performTextInput("aLi")
        compose.onNodeWithTag("choose_friend_${alice.id}").performClick()
        compose.onNodeWithTag("expense_title").assertExists()
        compose.onNodeWithTag("participant_${alice.id}").assertDoesNotExist()
        compose.onNodeWithTag("expense_title").performTextInput("Coffee")
        compose.onNodeWithTag("expense_amount").performTextInput("4")
        chooseCurrency("USD")
        compose.onNodeWithTag("expense_next").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("participant_${alice.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("participant_${alice.id}").performScrollTo()
        compose.onNodeWithTag("participant_${alice.id}").assertIsOn()
        assertTrue(repository.state.value.expenses.isEmpty())
        repository.close()
        context.deleteDatabase(name)
    }

    @Test fun groupsAddMenuOpensBothActionsAndDismissesWithoutWriting() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "home-add-menu-${System.nanoTime()}.db"
        val repository = LocalRepository(context, name)
        runBlocking { repository.setProfile("Taylor") }
        compose.setContent { FairshareApp(repository) }

        compose.onNodeWithTag("home_add_menu").assertIsDisplayed()
        compose.onNodeWithTag("home_add_group").assertDoesNotExist()
        compose.onNodeWithTag("global_add_expense").assertDoesNotExist()
        compose.onNodeWithTag("home_add_menu").performClick()
        compose.onNodeWithTag("home_add_group").assertIsDisplayed()
        compose.onNodeWithTag("global_add_expense").assertIsDisplayed()
        compose.onNodeWithTag("home_add_menu").performClick()
        compose.onNodeWithTag("home_add_group").assertDoesNotExist()
        compose.onNodeWithTag("global_add_expense").assertDoesNotExist()

        compose.onNodeWithTag("home_add_menu").performClick()
        compose.onNodeWithTag("home_add_menu_dismiss").performClick()
        compose.onNodeWithTag("home_add_group").assertDoesNotExist()
        compose.onNodeWithTag("home_add_menu").performClick()
        compose.onNodeWithTag("home_add_group").performClick()
        compose.onNodeWithTag("group_name").assertIsDisplayed()
        compose.onNodeWithTag("home_add_group").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("home_add_menu").performClick()
        compose.onNodeWithTag("global_add_expense").performClick()
        compose.onNodeWithTag("expense_destination_search").assertIsDisplayed()
        compose.onNodeWithTag("home_add_group").assertDoesNotExist()
        compose.onNodeWithText("New group").performClick()
        compose.onNodeWithTag("group_name").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("home_add_menu").performClick()
        compose.onNodeWithTag("nav_account").performClick()
        compose.onNodeWithTag("nav_groups").performClick()
        compose.onNodeWithTag("home_add_group").assertDoesNotExist()
        assertTrue(repository.state.value.groups.isEmpty())
        assertTrue(repository.state.value.expenses.isEmpty())
        repository.close()
        context.deleteDatabase(name)
    }

    /** Native IME inset animations can keep moving a displayed Compose button. */
    private fun waitForStableVisibleBounds(tag: String) {
        var previous: Rect? = null
        var stableSince = 0L
        compose.waitUntil(5000) {
            val node = compose.onNodeWithTag(tag)
            if (!node.isDisplayed()) {
                previous = null
                stableSince = 0L
                false
            } else {
                val current = node.fetchSemanticsNode().boundsInRoot
                val prior = previous
                val now = SystemClock.elapsedRealtime()
                val stable = prior != null &&
                    kotlin.math.abs(current.left - prior.left) < 0.5f &&
                    kotlin.math.abs(current.top - prior.top) < 0.5f &&
                    kotlin.math.abs(current.right - prior.right) < 0.5f &&
                    kotlin.math.abs(current.bottom - prior.bottom) < 0.5f
                if (!stable) stableSince = now
                previous = current
                stable && now - stableSince >= 180L
            }
        }
    }

    private fun chooseCurrency(code: String) {
        compose.onNodeWithTag("expense_currency").performScrollTo().performClick()
        compose.onNodeWithTag("expense_currency_search").performTextInput(code)
        compose.onNodeWithTag("expense_currency_$code").performClick()
    }
    private fun finishExpense(onSplit: () -> Unit = {}) {
        listOf(2, 3).forEach { nextStep ->
            // This scenario verifies routing and persisted context. Native IME
            // dismissal can move the footer after a stable bounds sample; invoke
            // its accessibility action once. ExpenseWizardTest separately covers
            // physical Next/Add taps and keyboard interactions.
            compose.onNodeWithTag("expense_next").performSemanticsAction(SemanticsActions.OnClick) { it() }
            try {
                compose.waitUntil(5000) {
                    compose.onAllNodes(hasTestTag("expense_step") and hasText("Step $nextStep of 3", substring = true)).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (failure: Throwable) {
                throw AssertionError("Expense did not reach step $nextStep.\n${compose.onRoot(useUnmergedTree = true).printToString()}", failure)
            }
            if (nextStep == 2) onSplit()
        }
        waitForStableVisibleBounds("expense_save")
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("expense_save").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitForCelebrationStart()
            compose.mainClock.advanceTimeBy(350)
            compose.onNodeWithTag("save_celebration").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(2_000)
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForCelebrationEnd()
    }
}
