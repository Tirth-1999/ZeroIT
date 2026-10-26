package org.fairshare.app

import androidx.compose.ui.test.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
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
class ExpenseWizardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun groupDefaultCanBeOverriddenAndMultiplePayersExactSplitReviewAndBackPreserveDraft() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = "wizard-${System.nanoTime()}.db"
        val repository = LocalRepository(context, db)
        runBlocking {
            repository.setProfile("Taylor")
            val groupId = repository.createGroup("Border crossing", "USD", listOf("Alice", "Bob"))
            val ids = repository.state.value.groups.single().memberIds
            repository.saveExpense(Expense("usd-start", groupId, "Before travel", 3000L, SELF_ID, ids.associateWith { 1000L }))
        }
        val before = repository.state.value
        val group = before.groups.single()
        val alice = before.members.single { it.name == "Alice" }.id
        val bob = before.members.single { it.name == "Bob" }.id
        var keyboard: SoftwareKeyboardController? = null
        compose.setContent {
            val controller = LocalSoftwareKeyboardController.current
            SideEffect { keyboard = controller }
            FairshareApp(repository)
        }
        compose.onNodeWithTag("group_${group.id}").performScrollTo().performClick()
        compose.onNodeWithTag("add_expense").performClick()
        compose.onNodeWithTag("expense_title").performTextInput("Train in France")
        compose.onNodeWithTag("expense_amount").performTextInput("30.00")
        compose.onNodeWithTag("payer_$SELF_ID").assertExists()
        compose.onNodeWithTag("participant_$SELF_ID").assertDoesNotExist()
        compose.onNodeWithTag("expense_currency").assertTextContains("USD", substring = true)
        compose.onNodeWithTag("expense_save").assertDoesNotExist()
        assertEquals(before, repository.state.value)
        compose.onNodeWithTag("expense_currency").performScrollTo().performClick()
        compose.onNodeWithTag("expense_currency_search").performTextInput("EUR")
        compose.onNodeWithTag("expense_currency_EUR").performClick()
        compose.onNodeWithTag("payer_$alice").performScrollTo().performClick()
        compose.onNodeWithTag("payer_amount_$SELF_ID").performScrollTo().performTextInput("20.00")
        compose.onNodeWithTag("payer_amount_$alice").performScrollTo().performTextInput("10.00")
        compose.runOnIdle { keyboard?.hide() }
        compose.captureEvidence("01-expense-details")
        compose.onNodeWithTag("expense_next").performClick()
        compose.captureEvidence("02-split-equal")
        compose.onNodeWithTag("participant_$SELF_ID").assertIsOn()
        compose.onNodeWithTag("participant_$alice").assertIsOn()
        compose.onNodeWithTag("split_mode_exact").performScrollTo().performClick()
        compose.onNodeWithTag("split_$SELF_ID").performScrollTo().performTextInput("5.00")
        compose.onNodeWithTag("split_$alice").performScrollTo().performTextInput("10.00")
        compose.onNodeWithTag("split_$bob").performScrollTo().performTextInput("15.00")
        compose.runOnIdle { keyboard?.hide() }
        compose.onNodeWithTag("split_total_feedback").performScrollTo()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("split_total_feedback").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty() && layouts.all { it.layoutInput.style.textAlign == androidx.compose.ui.text.style.TextAlign.Center })
        compose.captureEvidence("03-split-exact")
        compose.onNodeWithTag("expense_next").performClick()
        compose.captureEvidence("04-review")
        compose.onNodeWithTag("expense_save").assertExists()
        assertEquals(before, repository.state.value)
        compose.onNodeWithTag("expense_previous").performClick()
        compose.onNodeWithTag("split_$bob").performScrollTo().assertTextContains("15.00")
        compose.onNodeWithTag("expense_previous").performClick()
        compose.onNodeWithTag("expense_amount").assertTextContains("30.00")
        compose.onNodeWithTag("expense_currency").assertTextContains("EUR", substring = true)
        compose.onNodeWithTag("expense_next").performClick()
        compose.onNodeWithTag("expense_next").performClick()
        compose.onNodeWithTag("expense_save").assertIsDisplayed()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("expense_save").performClick()
        compose.waitUntil(5000) { repository.state.value.expenses.size == 2 }
        compose.waitForCelebrationStart()
        compose.onNodeWithTag("save_celebration").assertExists()
        val celebrationBounds = compose.onNodeWithTag("save_celebration").getUnclippedBoundsInRoot()
        val screenBounds = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue("Expense acknowledgement should cover the screen",
            celebrationBounds.right - celebrationBounds.left >= (screenBounds.right - screenBounds.left) * .99f &&
                celebrationBounds.bottom - celebrationBounds.top >= (screenBounds.bottom - screenBounds.top) * .95f)
        // Progress sampled at fixed frame times, avoiding auto-advance past the animation.
        listOf(80L, 120L, 140L, 200L, 160L).forEachIndexed { index, delta ->
            compose.mainClock.advanceTimeBy(delta)
            compose.captureEvidence("05-expense-save-frame-$index")
        }
        compose.mainClock.advanceTimeBy(200L)
        compose.onNodeWithTag("save_celebration").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.waitForCelebrationEnd()
        compose.onNodeWithTag("group_title").assertTextEquals("Border crossing")
        compose.onNodeWithTag("expense_edit").assertDoesNotExist()
        val after = repository.state.value
        val expense = after.expenses.single { it.id != "usd-start" }
        assertEquals("EUR", expense.currency)
        assertEquals(mapOf(SELF_ID to 2000L, alice to 1000L), expense.paidBy)
        assertEquals(mapOf(SELF_ID to 500L, alice to 1000L, bob to 1500L), expense.owed)
        assertEquals(2000L, Ledger.balances(after, group.id, "USD")[SELF_ID])
        assertEquals(1500L, Ledger.balances(after, group.id, "EUR")[SELF_ID])
        repository.close()
        context.deleteDatabase(db)
    }

    @Test fun addingFromScrolledGroupReturnsToNewestExpenseAtTop() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = "wizard-scroll-${System.nanoTime()}.db"
        val repository = LocalRepository(context, db)
        try {
            runBlocking {
                repository.setProfile("Taylor")
                val groupId = repository.createGroup("Long history", "USD", emptyList())
                repeat(20) { index ->
                    repository.saveExpense(Expense("old-$index", groupId, "Old expense $index", 100L,
                        SELF_ID, mapOf(SELF_ID to 100L), createdAt = index.toLong()))
                }
            }
            val group = repository.state.value.groups.single()
            compose.setContent { FairshareApp(repository) }
            compose.onNodeWithTag("group_${group.id}").performClick()
            compose.scrollLedgerItemToView("group_expense_old-0")
            compose.onNodeWithTag("group_expense_old-0").assertIsDisplayed()
            compose.onNodeWithTag("add_expense").performClick()
            compose.onNodeWithTag("expense_title").performTextInput("Newest expense")
            compose.onNodeWithTag("expense_amount").performTextInput("5")
            repeat(2) { compose.onNodeWithTag("expense_next").performSemanticsAction(SemanticsActions.OnClick) { it() } }
            // Commit the review-step recomposition before pausing its frame clock.
            compose.onNodeWithTag("expense_save").assertIsDisplayed().assertIsEnabled()
            compose.mainClock.autoAdvance = false
            try {
                compose.onNodeWithTag("expense_save").performSemanticsAction(SemanticsActions.OnClick) { it() }
                compose.waitForCelebrationStart()
                compose.mainClock.advanceTimeBy(2_000)
            } finally { compose.mainClock.autoAdvance = true }
            compose.waitForCelebrationEnd()
            compose.onNodeWithTag("group_title").assertTextEquals("Long history")
            val newest = repository.state.value.expenses.single { it.title == "Newest expense" }
            // Assert the entire feed starts at zero, not just that the new tile is visible.
            assertEquals(0f, compose.onNodeWithTag("group_expense_list").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value(), 0f)
            // No scroll helper here: the app must restore the list's initial position.
            compose.onNodeWithTag("group_expense_${newest.id}").assertIsDisplayed()
            compose.onNodeWithTag("group_expense_old-0").assertDoesNotExist()
        } finally { repository.close(); context.deleteDatabase(db) }
    }

    @Test fun remainderIsSuggestedAndOnlyAppliedExplicitlyWithCurrencySearch() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = "wizard-remainder-${System.nanoTime()}.db"
        val repository = LocalRepository(context, db)
        runBlocking {
            repository.setProfile("Taylor")
            repository.createGroup("Weekend", "EUR", listOf("Alice"))
        }
        val before = repository.state.value
        val group = before.groups.single()
        val alice = before.members.single { it.name == "Alice" }.id
        compose.setContent { FairshareApp(repository) }
        compose.onNodeWithTag("group_${group.id}").performScrollTo().performClick()
        compose.onNodeWithTag("add_expense").performClick()
        // Arithmetic is available before a description; the group currency is already selected.
        compose.onNodeWithTag("expense_amount").performTextInput("50")
        compose.onNodeWithTag("payer_$alice").performScrollTo().performClick()
        compose.onNodeWithTag("payer_amount_$SELF_ID").performScrollTo().performTextInput("25")
        compose.onNodeWithTag("payer_remaining_$alice").performScrollTo().assertTextContains("25.00 left", substring = true)
        compose.onNodeWithTag("payer_amount_$alice").performScrollTo().performTextInput("12")
        compose.onNodeWithTag("payer_remaining_$alice").assertDoesNotExist()
        compose.onNodeWithTag("payer_amount_$alice").assertTextContains("12")
        compose.onNodeWithTag("payer_amount_$alice").performTextClearance()
        compose.onNodeWithTag("payer_amount_$SELF_ID").performScrollTo().performTextReplacement("10")
        compose.onNodeWithTag("expense_amount").performScrollTo().performTextReplacement("23.23")
        compose.onNodeWithTag("payer_remaining_$alice").performScrollTo().assertTextContains("13.23 left", substring = true)
        compose.onNodeWithTag("expense_title").performScrollTo().performTextInput("Lunch")
        compose.onNodeWithTag("expense_currency").assertTextContains("EUR", substring = true).performClick()
        // Opening the picker offers a shortlist, while search can reach any supported code.
        compose.onNodeWithTag("expense_currency_more").assertExists()
        assertTrue(compose.onAllNodesWithTag("expense_currency_ZAR").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("expense_currency_search").performTextInput("USD")
        compose.onNodeWithTag("expense_currency_USD").performClick()
        compose.onNodeWithTag("payer_remaining_$alice").performScrollTo().assertTextContains("13.23", substring = true)
        compose.onNodeWithTag("expense_next").performClick()
        compose.onNodeWithTag("expense_step").assertTextContains("Step 1 of 3", substring = true)
        assertEquals(before, repository.state.value)
        // No silent fill: the explicit action is still necessary after a validation attempt.
        compose.onNodeWithTag("payer_remaining_$alice").performScrollTo().performClick()
        compose.onNodeWithTag("payer_amount_$alice").assertTextContains("13.23", substring = true)
        compose.onNodeWithTag("expense_next").performClick()
        compose.onNodeWithTag("split_total_feedback").assertTextContains("Equally", substring = true)
        // Participation belongs to step two and is independent from who paid.
        compose.onNodeWithTag("participant_$SELF_ID").performScrollTo().performClick()
        compose.onNodeWithTag("participant_$alice").performScrollTo().performClick()
        compose.onNodeWithTag("expense_next").performClick()
        compose.onNodeWithTag("expense_step").assertTextContains("Step 2 of 3", substring = true)
        compose.onNodeWithTag("expense_error").performScrollTo().assertTextEquals("Select at least one person to split between.")
        compose.onNodeWithTag("participant_$alice").performScrollTo().performClick()
        compose.onNodeWithTag("expense_next").performClick()
        compose.onNodeWithTag("expense_review_header").assertExists()
        compose.onNodeWithTag("expense_notes").performScrollTo().performTextInput("Lunch after the train\nAlice's treat next time")
        // Notes persist through the accessible save action; the preceding test
        // covers physical footer taps without native keyboard movement.
        compose.onNodeWithTag("expense_save").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5000) { repository.state.value.expenses.size == 1 }
        compose.onNodeWithTag("group_title").assertTextEquals("Weekend")
        compose.onNodeWithTag("expense_edit").assertDoesNotExist()
        assertEquals(mapOf(SELF_ID to 1000L, alice to 1323L), repository.state.value.expenses.single().paidBy)
        assertEquals(mapOf(alice to 2323L), repository.state.value.expenses.single().owed)
        assertEquals("Lunch after the train\nAlice's treat next time", repository.state.value.expenses.single().notes)
        repository.close()
        context.deleteDatabase(db)
    }

    @Test fun unchangedGroupDefaultSavesAndExistingForeignCurrencySurvivesEditing() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = "wizard-default-${System.nanoTime()}.db"
        val repository = LocalRepository(context, db)
        runBlocking {
            repository.setProfile("Taylor")
            val groupId = repository.createGroup("Tokyo", "JPY", listOf("Alice"))
            val ids = repository.state.value.groups.single().memberIds
            repository.saveExpense(Expense("foreign", groupId, "Euro booking", 2500L, SELF_ID,
                ids.associateWith { 1250L }, currency = "EUR"))
        }
        val group = repository.state.value.groups.single()
        compose.setContent { FairshareApp(repository) }
        compose.onNodeWithTag("group_${group.id}").performScrollTo().performClick()
        compose.onNodeWithTag("add_expense").performClick()
        compose.onNodeWithTag("expense_currency").assertTextContains("JPY", substring = true)
        compose.onNodeWithTag("expense_title").performTextInput("Station lunch")
        compose.onNodeWithTag("expense_amount").performTextInput("1500")
        compose.onNodeWithTag("expense_next").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("expense_next").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("expense_save").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5000) { repository.state.value.expenses.size == 2 }
        assertEquals("JPY", repository.state.value.expenses.single { it.id != "foreign" }.currency)
        compose.scrollLedgerItemToView("group_expense_foreign")
        compose.onNodeWithTag("group_expense_foreign").performClick()
        compose.onNodeWithTag("expense_edit").performScrollTo().performClick()
        compose.onNodeWithTag("expense_currency").assertTextContains("EUR", substring = true)
        compose.onNodeWithTag("expense_amount").assertTextContains("25.00")
        compose.onNodeWithTag("expense_title").performTextReplacement("Euro booking updated")
        compose.onNodeWithTag("expense_next").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("expense_next").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("expense_save").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5000) { repository.state.value.expenses.single { it.id == "foreign" }.title == "Euro booking updated" }
        val edited = repository.state.value.expenses.single { it.id == "foreign" }
        assertEquals("EUR", edited.currency)
        assertEquals(2500L, edited.amountMinor)
        assertEquals("JPY", repository.state.value.groups.single().currency)
        repository.close()
        context.deleteDatabase(db)
    }

}
