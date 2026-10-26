package org.fairshare.app

import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fairshare.app.data.LocalRepository
import org.fairshare.app.domain.Ledger
import org.fairshare.app.model.SELF_ID
import org.fairshare.app.ui.FairshareApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AcceptanceFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun createExpenseSettleDeleteRestoreAndChangeTheme() {
        val databaseName = "ui-${System.nanoTime()}.db"
        val repository = LocalRepository(ApplicationProvider.getApplicationContext(), databaseName)
        runBlocking { repository.load() }
        var keyboardController: SoftwareKeyboardController? = null
        lateinit var focusManager: androidx.compose.ui.focus.FocusManager
        compose.setContent {
            focusManager = androidx.compose.ui.platform.LocalFocusManager.current
            val currentKeyboardController = LocalSoftwareKeyboardController.current
            SideEffect { keyboardController = currentKeyboardController }
            FairshareApp(repository)
        }
        compose.onNodeWithTag("welcome_continue").performScrollTo().performClick()
        compose.onNodeWithTag("profile_name").performTextInput("Taylor")
        compose.onNodeWithTag("profile_continue").performScrollTo().performClick()
        compose.waitUntil(5000) { repository.state.value.onboardingComplete }
        compose.onNodeWithTag("create_group").performClick()
        compose.onNodeWithTag("group_name").performTextInput("Weekend test")
        // Match the existing pointer-based creation fixture: finish field layout
        // on the UI thread before saving, avoiding a semantics observer race.
        compose.runOnUiThread { focusManager.clearFocus(force = true) }
        compose.onNodeWithTag("group_save").performTouchInput { click() }
        compose.waitUntil(5000) { repository.state.value.groups.isNotEmpty() }
        compose.onNodeWithTag("group_members").performClick()
        compose.onNodeWithTag("add_members").performScrollTo().performClick()
        stagePerson("Alice")
        stagePerson("Bob")
        assertEquals(1, repository.state.value.groups.single().memberIds.size)
        compose.onNodeWithTag("member_next").performClick()
        compose.onNodeWithTag("member_confirm").performScrollTo().performClick()
        compose.waitUntil(5000) { repository.state.value.groups.single().memberIds.size == 3 }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("add_expense").performClick()
        compose.onNodeWithTag("expense_title").performTextInput("Dinner")
        compose.onNodeWithTag("expense_amount").performTextInput("30.00")
        chooseCurrency("USD")
        finishExpense()
        compose.waitUntil(5000) { repository.state.value.expenses.size == 1 }
        val group = repository.state.value.groups.single()
        assertEquals(2000L, Ledger.balances(repository.state.value, group.id)[SELF_ID])
        compose.onNodeWithTag("group_title").assertTextEquals("Weekend test")
        compose.scrollLedgerItemToView("group_expense_${repository.state.value.expenses.single().id}")
        compose.onNodeWithTag("group_expense_${repository.state.value.expenses.single().id}").performClick()
        compose.onNodeWithTag("expense_edit").performScrollTo().performClick()
        compose.onNodeWithTag("expense_amount").performTextReplacement("60.00")
        nextExpenseStep(2)
        compose.onNodeWithTag("split_mode_equal").performScrollTo().performClick()
        nextExpenseStep(3)
        clickWithCelebration("expense_save")
        compose.waitUntil(5000) { repository.state.value.expenses.single().amountMinor == 6000L }
        assertEquals(4000L, Ledger.balances(repository.state.value, group.id)[SELF_ID])
        compose.onNodeWithTag("expense_delete").performScrollTo().performClick()
        clickWithCelebration("confirm_delete")
        compose.waitUntil(5000) { repository.state.value.expenses.single().deleted }
        assertEquals(0L, Ledger.balances(repository.state.value, group.id)[SELF_ID])
        clickWithCelebration("expense_restore", scroll = true)
        compose.waitUntil(5000) { !repository.state.value.expenses.single().deleted }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("group_action_settle").performScrollTo().performClick()
        compose.onAllNodesWithTag("settle_up")[0].performScrollTo().performClick()
        compose.onNodeWithTag("settlement_people_summary").assertExists()
        val peopleCard = compose.onNodeWithTag("settlement_people_summary").getUnclippedBoundsInRoot()
        val changeLabel = compose.onNodeWithTag("settlement_change_people_label", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Change people must be centered within its card", kotlin.math.abs((peopleCard.left.value + peopleCard.right.value - changeLabel.left.value - changeLabel.right.value) / 2f) < 1f)
        compose.onNodeWithTag("settlement_from_self").assertDoesNotExist()
        compose.captureEvidence("06-settlement-summary")
        compose.onNodeWithTag("settlement_change_people").performClick()
        compose.onNodeWithTag("settlement_from_self").assertExists()
        compose.captureEvidence("07-settlement-people")
        compose.onNodeWithTag("settlement_change_people").performClick()
        compose.onNodeWithTag("settlement_from_self").assertDoesNotExist()
        compose.onNodeWithTag("settlement_amount").performScrollTo().performTextReplacement("5.00")
        compose.runOnIdle { keyboardController?.hide() }
        compose.waitForIdle()
        compose.onNodeWithTag("settlement_save").assertIsDisplayed()
        waitForStableVisibleBounds("settlement_save")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("settlement_save").performClick()
        compose.waitUntil(5000) { repository.state.value.settlements.size == 1 }
        compose.waitForCelebrationStart()
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithTag("save_celebration").assertIsDisplayed()
        compose.assertCelebrationMessageDisplayed("Payment recorded", "10-payment-recorded-fullscreen-before-text-check")
        val celebrationBounds = compose.onNodeWithTag("save_celebration").getUnclippedBoundsInRoot()
        val screenBounds = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue("Payment acknowledgement should cover the screen",
            celebrationBounds.right - celebrationBounds.left >= (screenBounds.right - screenBounds.left) * .99f &&
                celebrationBounds.bottom - celebrationBounds.top >= (screenBounds.bottom - screenBounds.top) * .95f)
        compose.captureEvidence("10-payment-recorded-fullscreen")
        compose.mainClock.autoAdvance = true
        compose.waitForCelebrationEnd()
        assertEquals(3500L, Ledger.balances(repository.state.value, group.id)[SELF_ID])
        compose.onNodeWithTag("group_recorded_payments").performScrollTo().performClick()
        compose.onNodeWithTag("delete_settlement_${repository.state.value.settlements.single().id}").performScrollTo().assertIsDisplayed()
        compose.captureEvidence("11-tinted-recorded-payments")
        compose.onNodeWithTag("group_tab_expenses").performClick()
        compose.onNodeWithTag("group_payment_${repository.state.value.settlements.single().id}").assertIsDisplayed()
        compose.captureEvidence("12-tinted-payment-feed")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("nav_account").performClick()
        compose.onNodeWithTag("theme_dark").performScrollTo().performClick()
        compose.waitUntil(5000) { repository.state.value.themeMode == "DARK" }
        compose.onNodeWithTag("nav_groups").performClick()
        compose.onNodeWithTag("group_${group.id}").performClick()
        val paymentTag = "group_payment_${repository.state.value.settlements.single().id}"
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNode(hasText("Alice paid Taylor") and hasAnyAncestor(hasTestTag(paymentTag)))
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Tinted payment cards need a light foreground in dark appearance", layouts.isNotEmpty() && layouts.all { it.layoutInput.style.color.luminance() > .5f })
        compose.captureEvidence("13-tinted-payment-dark")
        val before = repository.state.value
        repository.close()
        val reopened = LocalRepository(ApplicationProvider.getApplicationContext(), databaseName)
        runBlocking { reopened.load() }
        assertEquals(before, reopened.state.value)
        reopened.close()
        assertTrue(before.activity.size >= 5)
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
    private fun nextExpenseStep(nextStep: Int) {
        // This lifecycle test exercises the accessibility action; the wizard test
        // covers physical footer taps, including keyboard interactions.
        compose.onNodeWithTag("expense_next").performSemanticsAction(SemanticsActions.OnClick) { it() }
        try {
            compose.waitUntil(5000) {
                compose.onAllNodes(hasTestTag("expense_step") and hasText("Step $nextStep of 3", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            throw AssertionError("Expense did not reach step $nextStep.\n${compose.onRoot(useUnmergedTree = true).printToString()}", failure)
        }
    }

    private fun finishExpense() {
        nextExpenseStep(2)
        nextExpenseStep(3)
        waitForStableVisibleBounds("expense_save")
        clickWithCelebration("expense_save")
    }

    private fun clickWithCelebration(tag: String, scroll: Boolean = false) {
        // Keep auto-advance enabled until the preceding navigation/recomposition
        // has replaced Next with Save (or opened the confirmation/menu action).
        compose.waitForIdle()
        val node = compose.onNodeWithTag(tag)
        if (scroll) node.performScrollTo()
        node.assertExists().assertIsEnabled()
        compose.mainClock.autoAdvance = false
        try {
            node.performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitForCelebrationStart()
            compose.mainClock.advanceTimeBy(350)
            compose.onNodeWithTag("save_celebration").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(2_000)
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForCelebrationEnd()
    }
    private fun stagePerson(name: String) {
        compose.onNodeWithTag("member_new").performScrollTo().performClick()
        compose.onNodeWithTag("member_new_name").performTextReplacement(name)
        compose.onNodeWithTag("member_new_add").performClick()
    }

    @Test fun multiplePeopleSearchReviewCancelAndReuse() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "picker-${System.nanoTime()}.db"
        val repository = LocalRepository(context, databaseName)
        runBlocking {
            repository.setProfile("Taylor")
            repository.createGroup("Home", "USD", listOf("Alice", "Bob", "Charlie"))
            repository.createGroup("Trip", "USD", emptyList())
        }
        val before = repository.state.value
        val trip = before.groups.last()
        val alice = before.members.single { it.name == "Alice" }.id
        val bob = before.members.single { it.name == "Bob" }.id
        compose.setContent { FairshareApp(repository) }
        compose.onNodeWithTag("group_${trip.id}").performScrollTo().performClick()
        compose.onNodeWithTag("group_members").performClick()
        compose.onNodeWithTag("add_members").performScrollTo().performClick()
        compose.onNodeWithTag("member_select_$alice").performScrollTo().performClick()
        compose.onNodeWithTag("member_search").performTextInput("Bob")
        compose.onNodeWithTag("member_select_$bob").performScrollTo().performClick()
        compose.onNodeWithTag("member_search").performScrollTo().performTextReplacement("nobody")
        compose.onNodeWithTag("member_remove_$alice").assertExists()
        compose.onNodeWithTag("member_remove_$bob").assertExists()
        compose.onNodeWithTag("member_remove_$alice").performClick()
        compose.onNodeWithTag("member_search").performScrollTo().performTextClearance()
        compose.onNodeWithTag("member_select_$alice").performScrollTo().performClick()
        stagePerson("Dana")
        stagePerson("Eli")
        compose.onNodeWithTag("member_next").performClick()
        assertEquals(before, repository.state.value)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("member_remove_$alice").assertExists()
        compose.onNodeWithTag("member_remove_new_0").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("member_keep_editing").performClick()
        compose.onNodeWithTag("member_remove_$alice").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("member_discard").performClick()
        assertEquals(before, repository.state.value)
        compose.onNodeWithTag("add_members").performScrollTo().performClick()
        compose.onNodeWithTag("member_next").assertIsNotEnabled()
        compose.onNodeWithTag("member_select_$alice").performScrollTo().performClick()
        compose.onNodeWithTag("member_select_$bob").performScrollTo().performClick()
        stagePerson("Dana")
        stagePerson("Eli")
        compose.onNodeWithTag("member_next").performClick()
        compose.onNodeWithTag("member_review_remove_new_1").performScrollTo().performClick()
        compose.onNodeWithTag("member_edit_selection").performScrollTo().performClick()
        stagePerson("Eli")
        compose.onNodeWithTag("member_next").performClick()
        compose.onNodeWithTag("member_confirm").performScrollTo().performClick()
        compose.waitUntil(5000) { repository.state.value.groups.last().memberIds.size == 5 }
        val after = repository.state.value
        assertTrue(after.groups.last().memberIds.containsAll(listOf(alice, bob)))
        assertEquals(1, after.members.count { it.name == "Alice" })
        assertEquals(1, after.members.count { it.name == "Dana" })
        assertEquals(1, after.activity.count { it.groupId == trip.id && it.message.startsWith("Added members") })
        repository.close()
        val reopened = LocalRepository(context, databaseName)
        runBlocking { reopened.load() }
        assertEquals(after, reopened.state.value)
        reopened.close()
        context.deleteDatabase(databaseName)
    }

}
